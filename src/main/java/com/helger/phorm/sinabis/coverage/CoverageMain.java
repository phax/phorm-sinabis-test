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
package com.helger.phorm.sinabis.coverage;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.helger.collection.commons.CommonsArrayList;
import com.helger.collection.commons.ICommonsList;
import com.helger.phorm.sinabis.ManifestReader;
import com.helger.phorm.sinabis.SinabisEntry;

/**
 * Analyses the XML files listed in the Sinabis manifests and reports which invoice aspects are
 * covered - similar to the KoSIT XRechnung test suite "test overview". Works on the files only and
 * needs no running phorm.
 *
 * @author Philip Helger
 */
public final class CoverageMain
{
  private static final Logger LOGGER = LoggerFactory.getLogger (CoverageMain.class);
  private static final int TOP_COMBINATIONS = 20;

  /** Occurrence count and first example of a feature */
  private static final class FeatureStat
  {
    final AtomicLong m_aCount = new AtomicLong ();
    volatile String m_sExample;
    volatile int m_nExampleIdx = Integer.MAX_VALUE;

    void add (final SinabisEntry aEntry)
    {
      m_aCount.incrementAndGet ();
      // Keep the lowest index as the example, so the result does not depend on thread timing
      synchronized (this)
      {
        if (aEntry.getIdx () < m_nExampleIdx)
        {
          m_nExampleIdx = aEntry.getIdx ();
          m_sExample = aEntry.getXMLFile ();
        }
      }
    }
  }

  private CoverageMain ()
  {}

  private static @NonNull String _csv (final @NonNull String s)
  {
    return '"' + s.replace ("\"", "\"\"") + '"';
  }

  private static void _usage ()
  {
    System.err.println ("""
        Usage: java -cp phorm-sinabis-test.jar com.helger.phorm.sinabis.coverage.CoverageMain --data-dir <dir> [options]

          --data-dir <dir>     Sinabis dataset root, containing manifest/ and zugferd/ (required)
          --manifest <name>    Only read this manifest file, e.g. zb00000.jsonl
          --skip <n>           Skip the first n manifest entries (default 0)
          --limit <n>          Process at most n entries after skipping (default all)
          --threads <n>        Parallel threads (default: number of CPUs)
          --out-dir <dir>      Output directory (default ./coverage)
        """);
  }

  public static void main (final String [] aArgs) throws Exception
  {
    Path aDataDir = null;
    String sManifest = null;
    int nSkip = 0;
    int nLimit = Integer.MAX_VALUE;
    int nThreads = Runtime.getRuntime ().availableProcessors ();
    Path aOutDir = Paths.get ("coverage");
    try
    {
      for (int i = 0; i < aArgs.length; i += 2)
      {
        if (i + 1 >= aArgs.length)
          throw new IllegalArgumentException ("Missing value for " + aArgs[i]);
        final String sValue = aArgs[i + 1];
        switch (aArgs[i])
        {
          case "--data-dir" -> aDataDir = Paths.get (sValue);
          case "--manifest" -> sManifest = sValue;
          case "--skip" -> nSkip = Integer.parseInt (sValue);
          case "--limit" -> nLimit = Integer.parseInt (sValue);
          case "--threads" -> nThreads = Integer.parseInt (sValue);
          case "--out-dir" -> aOutDir = Paths.get (sValue);
          default -> throw new IllegalArgumentException ("Unknown option " + aArgs[i]);
        }
      }
      if (aDataDir == null)
        throw new IllegalArgumentException ("--data-dir is required");
    }
    catch (final IllegalArgumentException ex)
    {
      System.err.println (ex.getMessage ());
      _usage ();
      System.exit (2);
      return;
    }

    final Path aFinalDataDir = aDataDir;
    final ICommonsList <SinabisEntry> aAll = ManifestReader.readAll (aDataDir.resolve ("manifest"), sManifest);
    final int nFrom = Math.min (nSkip, aAll.size ());
    final int nTo = (int) Math.min ((long) nFrom + nLimit, aAll.size ());
    final ICommonsList <SinabisEntry> aEntries = new CommonsArrayList <> (aAll.subList (nFrom, nTo));
    Files.createDirectories (aOutDir);

    final Map <String, FeatureStat> aFeatures = new ConcurrentHashMap <> ();
    // Description (the "fingerprint" of an invoice) -> count
    final Map <String, FeatureStat> aCombinations = new ConcurrentHashMap <> ();
    final AtomicLong aErrors = new AtomicLong ();
    final AtomicLong aDone = new AtomicLong ();
    final ThreadLocal <InvoiceFeatureExtractor> aExtractors = ThreadLocal.withInitial (InvoiceFeatureExtractor::new);

    try (final BufferedWriter aCSV = Files.newBufferedWriter (aOutDir.resolve ("invoices.csv"), StandardCharsets.UTF_8))
    {
      aCSV.write ("idx,file,description");
      aCSV.newLine ();

      final ExecutorService aExecutor = Executors.newFixedThreadPool (nThreads);
      for (final SinabisEntry aEntry : aEntries)
        aExecutor.execute (() -> {
          try (final InputStream aIS = Files.newInputStream (aFinalDataDir.resolve (aEntry.getXMLFile ())))
          {
            final Set <String> aSet = aExtractors.get ().extract (aIS);
            for (final String sFeature : aSet)
              aFeatures.computeIfAbsent (sFeature, _ -> new FeatureStat ()).add (aEntry);
            final String sDesc = InvoiceFeatureExtractor.describe (aSet);
            aCombinations.computeIfAbsent (sDesc, _ -> new FeatureStat ()).add (aEntry);
            synchronized (aCSV)
            {
              aCSV.write (aEntry.getIdx () + "," + _csv (aEntry.getXMLFile ()) + "," + _csv (sDesc));
              aCSV.newLine ();
            }
          }
          catch (final Exception ex)
          {
            aErrors.incrementAndGet ();
            LOGGER.error ("Failed to analyse '" + aEntry.getXMLFile () + "': " + ex.getMessage ());
          }
          final long n = aDone.incrementAndGet ();
          if (n % 5000 == 0)
            LOGGER.info ("Analysed " + n + "/" + aEntries.size ());
        });
      aExecutor.shutdown ();
      aExecutor.awaitTermination (365, TimeUnit.DAYS);
    }

    _writeMarkdown (aOutDir.resolve ("coverage.md"), aEntries.size (), aErrors.get (), aFeatures, aCombinations);
    LOGGER.info ("Analysed " +
                 aEntries.size () +
                 " invoices (" +
                 aErrors.get () +
                 " failed), " +
                 aFeatures.size () +
                 " features, " +
                 aCombinations.size () +
                 " distinct combinations. Results in " +
                 aOutDir.toAbsolutePath ());
  }

  private static void _writeMarkdown (final Path aFile,
                                      final int nInvoices,
                                      final long nErrors,
                                      final Map <String, FeatureStat> aFeatures,
                                      final Map <String, FeatureStat> aCombinations) throws IOException
  {
    final StringBuilder aSB = new StringBuilder ();
    aSB.append ("# Invoice aspects covered by the Sinabis test files\n\n");
    aSB.append ("* Invoices analysed: ").append (nInvoices).append ('\n');
    aSB.append ("* Files that could not be analysed: ").append (nErrors).append ('\n');
    aSB.append ("* Distinct feature combinations: ").append (aCombinations.size ()).append ("\n\n");

    final List <String> aGroups = List.of (InvoiceFeatureExtractor.G_DOC,
                                           InvoiceFeatureExtractor.G_LINES,
                                           InvoiceFeatureExtractor.G_LINEDETAILS,
                                           InvoiceFeatureExtractor.G_VAT,
                                           InvoiceFeatureExtractor.G_PAYMENT,
                                           InvoiceFeatureExtractor.G_REF,
                                           InvoiceFeatureExtractor.G_PARTY,
                                           InvoiceFeatureExtractor.G_DELIVERY,
                                           InvoiceFeatureExtractor.G_PERIOD,
                                           InvoiceFeatureExtractor.G_AMOUNTS);
    for (final String sGroup : aGroups)
    {
      aSB.append ("## ")
         .append (sGroup)
         .append ("\n\n| Aspect | Invoices | % | First example |\n|---|---:|---:|---|\n");
      aFeatures.entrySet ()
               .stream ()
               .filter (e -> e.getKey ().startsWith (sGroup + ": "))
               .sorted (Map.Entry.comparingByKey ())
               .forEach (e -> aSB.append ("| ")
                                 .append (e.getKey ().substring (sGroup.length () + 2))
                                 .append (" | ")
                                 .append (e.getValue ().m_aCount.get ())
                                 .append (" | ")
                                 .append (String.format (Locale.ROOT,
                                                         "%.2f",
                                                         Double.valueOf (100d *
                                                                         e.getValue ().m_aCount.get () /
                                                                         nInvoices)))
                                 .append (" | `")
                                 .append (e.getValue ().m_sExample)
                                 .append ("` |\n"));
      aSB.append ('\n');
    }

    aSB.append ("## Most common combinations\n\n| Description | Invoices | First example |\n|---|---:|---|\n");
    aCombinations.entrySet ()
                 .stream ()
                 .sorted ((a, b) -> Long.compare (b.getValue ().m_aCount.get (), a.getValue ().m_aCount.get ()))
                 .limit (TOP_COMBINATIONS)
                 .forEach (e -> aSB.append ("| ")
                                   .append (e.getKey ())
                                   .append (" | ")
                                   .append (e.getValue ().m_aCount.get ())
                                   .append (" | `")
                                   .append (e.getValue ().m_sExample)
                                   .append ("` |\n"));
    Files.writeString (aFile, aSB.toString (), StandardCharsets.UTF_8);
  }
}
