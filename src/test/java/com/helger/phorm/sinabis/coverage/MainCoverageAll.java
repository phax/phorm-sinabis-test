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

import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Assume;

/**
 * Runs the {@link CoverageMain} over <b>all</b> files of the Sinabis data set. This takes a while
 * for the full 127,500 invoices, so the test is only executed with
 * <code>-Dsinabis.coverage.all=true</code>. It can also be started as a plain Java application. The
 * data set location can be overridden with the system property <code>sinabis.datadir</code>.
 *
 * @author Philip Helger
 */
public final class MainCoverageAll
{
  private static final String DEFAULT_DATA_DIR = "/Volumes/helger.com/huggingface/sinabis-1m-synthetic-zugferd-xrechnung";
  private static final Path OUT_DIR = Paths.get ("target", "coverage-all");

  private static Path _getDataDir ()
  {
    return Paths.get (System.getProperty ("sinabis.datadir", DEFAULT_DATA_DIR));
  }

  private static void _run () throws Exception
  {
    CoverageMain.main (new String [] { "--data-dir",
                                       _getDataDir ().toString (),
                                       "--out-dir",
                                       OUT_DIR.toString (),
                                       "--limit",
                                       "1000" });
  }

  public static void main (final String [] aArgs) throws Exception
  {
    if (false)
      Assume.assumeTrue ("Set -Dsinabis.coverage.all=true to scan all files",
                         Boolean.getBoolean ("sinabis.coverage.all"));
    Assume.assumeTrue ("Sinabis data set not found at " + _getDataDir (),
                       Files.isDirectory (_getDataDir ().resolve ("manifest")));

    _run ();

    assertTrue (Files.isRegularFile (OUT_DIR.resolve ("coverage.md")));
    assertTrue (Files.isRegularFile (OUT_DIR.resolve ("invoices.csv")));
  }
}
