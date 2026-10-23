/*
 * Copyright (C) 2026 Philip Helger (www.helger.com)
 * philip[at]helger[dot]com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.helger.phorm.sinabis;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.hc.core5.util.Timeout;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.helger.base.io.nonblocking.NonBlockingBufferedWriter;
import com.helger.base.timing.StopWatch;
import com.helger.collection.commons.CommonsArrayList;
import com.helger.collection.commons.CommonsEnumMap;
import com.helger.collection.commons.ICommonsList;
import com.helger.collection.commons.ICommonsMap;
import com.helger.datetime.helper.PDTFactory;
import com.helger.diagnostics.error.IError;
import com.helger.httpclient.HttpClientSettings;
import com.helger.io.file.FileHelper;
import com.helger.json.IJsonObject;
import com.helger.json.JsonObject;
import com.helger.json.serialize.JsonWriter;
import com.helger.json.serialize.JsonWriterSettings;
import com.helger.phorm.client.PhormClient;
import com.helger.phorm.client.PhormClientException;
import com.helger.phorm.client.PhormValidationResult;

/**
 * Runs all files listed in the Sinabis manifests against a phorm instance, using N threads. Checks
 * that every file is considered valid and measures the validation time.
 *
 * @author Philip Helger
 */
public final class Main
{
  private static final Logger LOGGER = LoggerFactory.getLogger (Main.class);
  private static final int MAX_ERROR_IDS_IN_CSV = 5;

  private static final class Config
  {
    Path m_aDataDir;
    String m_sURL;
    String m_sToken;
    int m_nThreads = 8;
    boolean m_bXML = true;
    boolean m_bPDF = true;
    int m_nSkip = 0;
    int m_nLimit = Integer.MAX_VALUE;
    int m_nWarmup = 0;
    String m_sCountry = "DE";
    int m_nTimeoutSecs = 120;
    Path m_aOutDir = Paths.get ("results");
    String m_sManifest;
  }

  private Main ()
  {}

  private static void _usage ()
  {
    System.err.println ("""
        Usage: java -jar phorm-sinabis-test.jar --data-dir <dir> --url <phorm base URL> [options]

          --data-dir <dir>     Sinabis dataset root, containing manifest/ and zugferd/ (required)
          --url <url>          phorm base URL, e.g. http://localhost:8080 (required)
          --token <token>      X-Token header value
          --threads <n>        Parallel threads (default 8)
          --mode <m>           xml | pdf | both (default both)
          --manifest <name>    Only read this manifest file, e.g. zb00000.jsonl
          --skip <n>           Skip the first n manifest entries (default 0)
          --limit <n>          Process at most n entries after skipping (default all)
          --warmup <n>         Run n additional entries first, not counted (default 0)
          --country <c>        country for /api/hybrid_validate (default DE)
          --timeout-sec <n>    HTTP response timeout (default 120)
          --out-dir <dir>      Output directory (default ./results)
        """);
  }

  @NonNull
  private static Config _parse (final String [] aArgs)
  {
    final Config ret = new Config ();
    for (int i = 0; i < aArgs.length; i++)
    {
      final String sKey = aArgs[i];
      if (i + 1 >= aArgs.length)
        throw new IllegalArgumentException ("Missing value for " + sKey);
      final String sValue = aArgs[++i];
      switch (sKey)
      {
        case "--data-dir":
          ret.m_aDataDir = Paths.get (sValue);
          break;
        case "--url":
          ret.m_sURL = sValue;
          break;
        case "--token":
          ret.m_sToken = sValue;
          break;
        case "--threads":
          ret.m_nThreads = Integer.parseInt (sValue);
          break;
        case "--mode":
          // xml or both
          ret.m_bXML = !sValue.equals ("pdf");
          // pdf or both
          ret.m_bPDF = !sValue.equals ("xml");
          if (!sValue.equals ("xml") && !sValue.equals ("pdf") && !sValue.equals ("both"))
            throw new IllegalArgumentException ("Invalid --mode '" + sValue + "'");
          break;
        case "--manifest":
          ret.m_sManifest = sValue;
          break;
        case "--skip":
          ret.m_nSkip = Integer.parseInt (sValue);
          break;
        case "--limit":
          ret.m_nLimit = Integer.parseInt (sValue);
          break;
        case "--warmup":
          ret.m_nWarmup = Integer.parseInt (sValue);
          break;
        case "--country":
          ret.m_sCountry = sValue;
          break;
        case "--timeout-sec":
          ret.m_nTimeoutSecs = Integer.parseInt (sValue);
          break;
        case "--out-dir":
          ret.m_aOutDir = Paths.get (sValue);
          break;
        default:
          throw new IllegalArgumentException ("Unknown option " + sKey);
      }
    }
    if (ret.m_aDataDir == null || ret.m_sURL == null)
      throw new IllegalArgumentException ("--data-dir and --url are required");
    if (ret.m_nThreads < 1)
      throw new IllegalArgumentException ("--threads must be >= 1");
    return ret;
  }

  private static String _csv (final String s)
  {
    return '"' + s.replace ("\"", "\"\"") + '"';
  }

  /** Shared state of a run */
  private static final class Run
  {
    final Config m_aCfg;
    final PhormClient m_aClient;
    final ICommonsMap <ECallKind, KindStats> m_aStats = new CommonsEnumMap <> (ECallKind.class);
    final NonBlockingBufferedWriter m_aCSV;
    final AtomicLong m_aDone = new AtomicLong ();
    final long m_nTotalCalls;
    final long m_nStartNanos = System.nanoTime ();

    Run (final Config aCfg, final PhormClient aClient, final NonBlockingBufferedWriter aCSV, final int nEntries)
    {
      m_aCfg = aCfg;
      m_aClient = aClient;
      m_aCSV = aCSV;
      if (aCfg.m_bXML)
        m_aStats.put (ECallKind.XML, new KindStats (ECallKind.XML, nEntries));
      if (aCfg.m_bPDF)
        m_aStats.put (ECallKind.PDF, new KindStats (ECallKind.PDF, nEntries));
      m_nTotalCalls = (long) nEntries * m_aStats.size ();
    }

    void csv (final ECallKind eKind,
              final SinabisEntry aEntry,
              final KindStats.EOutcome eOutcome,
              final long nClientMillis,
              final long nServerMillis,
              final int nErrorCount,
              final String sErrorIDs,
              final String sDetail)
    {
      final String sLine = eKind.getID () +
                           ',' +
                           aEntry.getIdx () +
                           ',' +
                           _csv (aEntry.getFile (eKind)) +
                           ',' +
                           eOutcome +
                           ',' +
                           nClientMillis +
                           ',' +
                           nServerMillis +
                           ',' +
                           nErrorCount +
                           ',' +
                           _csv (sErrorIDs) +
                           ',' +
                           _csv (sDetail);
      synchronized (m_aCSV)
      {
        try
        {
          m_aCSV.write (sLine);
          m_aCSV.newLine ();
        }
        catch (final IOException ex)
        {
          throw new IllegalStateException ("Failed to write CSV", ex);
        }
      }
    }

    void progress ()
    {
      final long nDone = m_aDone.incrementAndGet ();
      if (nDone % 1000 == 0 || nDone == m_nTotalCalls)
      {
        final double dSecs = (System.nanoTime () - m_nStartNanos) / 1e9;
        LOGGER.info ("Progress: " +
                     nDone +
                     "/" +
                     m_nTotalCalls +
                     " calls, " +
                     String.format (Locale.ROOT, "%.1f", Double.valueOf (nDone / dSecs)) +
                     " calls/s");
      }
    }
  }

  private static void _processOne (final Run aRun,
                                   final Path aDataDir,
                                   final SinabisEntry aEntry,
                                   final ECallKind eKind,
                                   final boolean bRecord)
  {
    final KindStats aStats = aRun.m_aStats.get (eKind);

    // File reading is done outside the timed section
    final byte [] aPayload;
    try
    {
      aPayload = Files.readAllBytes (aDataDir.resolve (aEntry.getFile (eKind)));
    }
    catch (final IOException ex)
    {
      if (bRecord)
      {
        aStats.addFileError ();
        aRun.csv (eKind, aEntry, KindStats.EOutcome.FILE_ERROR, -1, -1, 0, "", ex.toString ());
        aRun.progress ();
      }
      return;
    }

    final StopWatch aSW = StopWatch.createdStarted ();
    try
    {
      // Main validation
      final PhormValidationResult aResult = switch (eKind)
      {
        case XML -> aRun.m_aClient.determineAndValidate (aPayload);
        case PDF -> aRun.m_aClient.hybridValidate (aPayload, aRun.m_aCfg.m_sCountry);
      };
      final long nClientMillis = aSW.stopAndGetMillis ();
      if (!bRecord)
        return;

      final long nServerMillis = aResult.getInvocationDurationMillis ();
      final boolean bValid = aResult.isSuccess ();
      final Set <String> aErrorIDs = new LinkedHashSet <> ();
      int nErrorCount = 0;
      for (final IError aError : aResult.getAllErrors ())
      {
        nErrorCount++;
        final String sID = aError.getErrorID ();
        aErrorIDs.add (sID == null || sID.isEmpty () ? "(no-id)" : sID);
      }
      final String sVESID = aResult.hasDocumentDetails () ? aResult.getDocumentDetails ().getVESID () : null;

      aStats.addVerdict (bValid, aPayload.length, nClientMillis, nServerMillis, aErrorIDs, sVESID);
      aRun.csv (eKind,
                aEntry,
                bValid ? KindStats.EOutcome.VALID : KindStats.EOutcome.INVALID,
                nClientMillis,
                nServerMillis,
                nErrorCount,
                String.join (";", aErrorIDs.stream ().limit (MAX_ERROR_IDS_IN_CSV).toList ()),
                sVESID == null ? "" : sVESID);
    }
    catch (final PhormClientException ex)
    {
      if (!bRecord)
        return;
      final long nClientMillis = aSW.stopAndGetMillis ();
      aStats.addCallError (ex.getErrorType ().getID ());
      aRun.csv (eKind,
                aEntry,
                KindStats.EOutcome.CALL_ERROR,
                nClientMillis,
                -1,
                0,
                ex.getErrorType ().getID (),
                String.valueOf (ex.getMessage ()));
    }
    catch (final RuntimeException ex)
    {
      if (!bRecord)
        return;
      aStats.addCallError ("UNEXPECTED_" + ex.getClass ().getSimpleName ());
      aRun.csv (eKind, aEntry, KindStats.EOutcome.CALL_ERROR, -1, -1, 0, "UNEXPECTED", ex.toString ());
    }
    if (bRecord)
      aRun.progress ();
  }

  private static void _runAll (final Run aRun,
                               final Path aDataDir,
                               final List <SinabisEntry> aEntries,
                               final boolean bRecord) throws InterruptedException
  {
    final ExecutorService aExecutor = Executors.newFixedThreadPool (aRun.m_aCfg.m_nThreads);
    for (final SinabisEntry aEntry : aEntries)
      aExecutor.execute (() -> {
        for (final ECallKind eKind : aRun.m_aStats.keySet ())
          _processOne (aRun, aDataDir, aEntry, eKind, bRecord);
      });
    aExecutor.shutdown ();
    aExecutor.awaitTermination (365, TimeUnit.DAYS);
  }

  private static void _writeSummaryMarkdown (final Path aFile,
                                             final IJsonObject aJson,
                                             final Run aRun,
                                             final long nWallMillis) throws IOException
  {
    final StringBuilder aSB = new StringBuilder ();
    aSB.append ("# phorm validation test with the Sinabis 1M XRechnung / ZUGFeRD data set\n\n");
    aSB.append ("| Setting | Value |\n|---|---|\n");
    aSB.append ("| Started | ").append (aJson.getAsString ("started")).append (" |\n");
    aSB.append ("| phorm URL | ").append (aRun.m_aCfg.m_sURL).append (" |\n");
    aSB.append ("| Threads | ").append (aRun.m_aCfg.m_nThreads).append (" |\n");
    aSB.append ("| Invoices | ").append (aJson.getAsInt ("invoices", 0)).append (" |\n");
    aSB.append ("| Wall time | ")
       .append (String.format (Locale.ROOT, "%.1f s", Double.valueOf (nWallMillis / 1000d)))
       .append (" |\n");
    aSB.append ("| Client | ").append (aJson.getAsString ("client")).append (" |\n\n");

    for (final Map.Entry <ECallKind, KindStats> aEntry : aRun.m_aStats.entrySet ())
    {
      final IJsonObject aKind = aJson.getAsObject (aEntry.getKey ().getID ());
      final IJsonObject aClient = aKind.getAsObject ("client_latency");
      final IJsonObject aServer = aKind.getAsObject ("server_latency");
      aSB.append ("## ")
         .append (aEntry.getKey ().getID ().toUpperCase ())
         .append (" (`")
         .append (aEntry.getKey ().getAPI ())
         .append ("`)\n\n");
      aSB.append ("| Metric | Value |\n|---|---|\n");
      for (final String sKey : new String [] { "total", "valid", "invalid", "call_error", "file_error" })
        aSB.append ("| ").append (sKey).append (" | ").append (aKind.getAsLong (sKey, 0)).append (" |\n");
      aSB.append ("| invalid % | ")
         .append (String.format (Locale.ROOT, "%.4f", Double.valueOf (aKind.getAsDouble ("invalid_percent", 0))))
         .append (" |\n");
      aSB.append ("| throughput | ")
         .append (String.format (Locale.ROOT, "%.1f / s", Double.valueOf (aKind.getAsDouble ("throughput_per_second", 0))))
         .append (" |\n\n");
      aSB.append ("| Latency (ms) | avg | median | p90 | p95 | p99 | max |\n|---|---|---|---|---|---|---|\n");
      for (final Map.Entry <String, IJsonObject> aLat : Map.of ("client (wall clock)",
                                                                aClient,
                                                                "server (reported)",
                                                                aServer).entrySet ())
      {
        final IJsonObject a = aLat.getValue ();
        aSB.append ("| ").append (aLat.getKey ());
        for (final String sKey : new String [] { "avg_ms", "median_ms", "p90_ms", "p95_ms", "p99_ms", "max_ms" })
          aSB.append (" | ").append (String.format (Locale.ROOT, "%.1f", Double.valueOf (a.getAsDouble (sKey, Double.NaN))));
        aSB.append (" |\n");
      }
      aSB.append ("\nFindings by error ID (invalid documents only): ")
         .append (aKind.getAsObject ("error_ids").size ())
         .append (" distinct, see summary.json\n\n");
    }
    Files.writeString (aFile, aSB.toString (), StandardCharsets.UTF_8);
  }

  public static void main (final String [] aArgs) throws Exception
  {
    final Config aCfg;
    try
    {
      aCfg = _parse (aArgs);
    }
    catch (final IllegalArgumentException ex)
    {
      System.err.println (ex.getMessage ());
      _usage ();
      System.exit (2);
      return;
    }

    final ICommonsList <SinabisEntry> aAll = ManifestReader.readAll (aCfg.m_aDataDir.resolve ("manifest"),
                                                                     aCfg.m_sManifest);
    final int nFrom = Math.min (aCfg.m_nSkip, aAll.size ());
    final int nTo = (int) Math.min ((long) nFrom + aCfg.m_nLimit, aAll.size ());
    final ICommonsList <SinabisEntry> aMeasured = new CommonsArrayList <> (aAll.subList (nFrom, nTo));
    final ICommonsList <SinabisEntry> aWarmup = new CommonsArrayList <> (aAll.subList (nTo,
                                                                                       Math.min (nTo + aCfg.m_nWarmup,
                                                                                                 aAll.size ())));
    Files.createDirectories (aCfg.m_aOutDir);

    final HttpClientSettings aHCS = new HttpClientSettings ();
    aHCS.setResponseTimeout (Timeout.ofSeconds (aCfg.m_nTimeoutSecs));

    try (final PhormClient aClient = PhormClient.builder ()
                                                .baseURL (aCfg.m_sURL)
                                                .token (aCfg.m_sToken)
                                                .httpClientSettings (aHCS)
                                                .build ();
         final NonBlockingBufferedWriter aCSV = FileHelper.getBufferedWriter (aCfg.m_aOutDir.resolve ("results.csv")
                                                                                            .toFile (),
                                                                              StandardCharsets.UTF_8))
    {
      aCSV.write ("kind,idx,file,outcome,client_ms,server_ms,error_count,error_ids,detail");
      aCSV.newLine ();

      // Fail early if the service is not reachable
      final int nVESIDs = aClient.getAllVESIDs ().size ();
      LOGGER.info ("phorm at " + aCfg.m_sURL + " knows " + nVESIDs + " VESIDs");

      if (!aWarmup.isEmpty ())
      {
        LOGGER.info ("Warm-up with " + aWarmup.size () + " invoices (not counted)");
        _runAll (new Run (aCfg, aClient, aCSV, aWarmup.size ()), aCfg.m_aDataDir, aWarmup, false);
      }

      LOGGER.info ("Starting measured run: " +
                   aMeasured.size () +
                   " invoices, " +
                   aCfg.m_nThreads +
                   " threads, mode" +
                   (aCfg.m_bXML ? " xml" : "") +
                   (aCfg.m_bPDF ? " pdf" : ""));
      LOGGER.info ("Expected end time not before " +
                   PDTFactory.getCurrentLocalDateTime ().plus ((long) (aMeasured.size () * 2.5), ChronoUnit.MILLIS));
      final Run aRun = new Run (aCfg, aClient, aCSV, aMeasured.size ());
      final String sStarted = Instant.now ().toString ();
      final long nStart = System.nanoTime ();
      _runAll (aRun, aCfg.m_aDataDir, aMeasured, true);
      final long nWallMillis = (System.nanoTime () - nStart) / 1_000_000L;
      aCSV.flush ();

      final IJsonObject aJson = new JsonObject ();
      aJson.add ("started", sStarted);
      aJson.add ("phorm_url", aCfg.m_sURL);
      aJson.add ("phorm_vesid_count", nVESIDs);
      aJson.add ("threads", aCfg.m_nThreads);
      aJson.add ("invoices", aMeasured.size ());
      aJson.add ("warmup_invoices", aWarmup.size ());
      aJson.add ("wall_millis", nWallMillis);
      aJson.add ("client",
                 "Java " +
                           System.getProperty ("java.version") +
                           ", " +
                           System.getProperty ("os.name") +
                           " " +
                           System.getProperty ("os.arch") +
                           ", " +
                           Runtime.getRuntime ().availableProcessors () +
                           " CPUs");
      aJson.add ("hybrid_country", aCfg.m_sCountry);
      for (final Map.Entry <ECallKind, KindStats> aEntry : aRun.m_aStats.entrySet ())
        aJson.add (aEntry.getKey ().getID (), aEntry.getValue ().getAsJson (nWallMillis));

      Files.writeString (aCfg.m_aOutDir.resolve ("summary.json"),
                         new JsonWriter (JsonWriterSettings.DEFAULT_SETTINGS_FORMATTED).writeAsString (aJson),
                         StandardCharsets.UTF_8);
      _writeSummaryMarkdown (aCfg.m_aOutDir.resolve ("summary.md"), aJson, aRun, nWallMillis);
      LOGGER.info ("Done in " + nWallMillis + " ms. Results in " + aCfg.m_aOutDir.toAbsolutePath ());
    }
  }
}
