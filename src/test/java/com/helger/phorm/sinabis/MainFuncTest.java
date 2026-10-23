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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.stream.Stream;

import org.junit.Assume;
import org.junit.Test;

/**
 * Runs {@link Main} with 100 invoices against a running phorm instance. The test is skipped if the
 * data set or phorm is not available. The defaults can be overridden with the system properties
 * <code>sinabis.datadir</code>, <code>phorm.url</code> and <code>phorm.token</code>.
 *
 * @author Philip Helger
 */
public final class MainFuncTest
{
  private static final String DEFAULT_DATA_DIR = "/Volumes/helger.com/huggingface/sinabis-1m-synthetic-zugferd-xrechnung";
  private static final String DEFAULT_URL = "http://localhost:8090";
  private static final String DEFAULT_TOKEN = "phorm-dev-token";

  private static boolean _isPhormReachable (final String sURL)
  {
    try
    {
      final HttpResponse <String> aResponse = HttpClient.newHttpClient ()
                                                        .send (HttpRequest.newBuilder (URI.create (sURL + "/ping"))
                                                                          .timeout (Duration.ofSeconds (3))
                                                                          .build (),
                                                               HttpResponse.BodyHandlers.ofString ());
      return aResponse.statusCode () == 200;
    }
    catch (final Exception ex)
    {
      return false;
    }
  }

  @Test
  public void testRun100Invoices () throws Exception
  {
    final Path aDataDir = Paths.get (System.getProperty ("sinabis.datadir", DEFAULT_DATA_DIR));
    final String sURL = System.getProperty ("phorm.url", DEFAULT_URL);
    final String sToken = System.getProperty ("phorm.token", DEFAULT_TOKEN);
    Assume.assumeTrue ("Sinabis data set not found at " + aDataDir, Files.isDirectory (aDataDir.resolve ("manifest")));
    Assume.assumeTrue ("phorm not reachable at " + sURL, _isPhormReachable (sURL));

    final Path aOutDir = Paths.get ("target", "sinabis-test-results");
    final long nLimit = 10_000;
    Main.main (new String [] { "--data-dir",
                               aDataDir.toString (),
                               "--url",
                               sURL,
                               "--token",
                               sToken,
                               "--threads",
                               "8",
                               // "--manifest",
                               // "zb00000.jsonl",
                               "--limit",
                               Long.toString (nLimit),
                               "--out-dir",
                               aOutDir.toString () });

    assertTrue (Files.isRegularFile (aOutDir.resolve ("results.csv")));
    assertTrue (Files.isRegularFile (aOutDir.resolve ("summary.json")));
    assertTrue (Files.isRegularFile (aOutDir.resolve ("summary.md")));

    // 100 invoices x 2 kinds + header
    // Stream the lines, as the file gets large for a full run
    try (final Stream <String> aLines = Files.lines (aOutDir.resolve ("results.csv")))
    {
      assertEquals ((2L * nLimit) + 1, aLines.count ());
    }
  }
}
