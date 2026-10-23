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

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.helger.base.string.StringHelper;
import com.helger.collection.commons.CommonsArrayList;
import com.helger.collection.commons.ICommonsList;
import com.helger.json.IJsonObject;
import com.helger.json.serialize.JsonReader;

/**
 * Reads the Sinabis <code>manifest/zbNNNNN.jsonl</code> files.
 *
 * @author Philip Helger
 */
public final class ManifestReader
{
  private static final Logger LOGGER = LoggerFactory.getLogger (ManifestReader.class);

  private ManifestReader ()
  {}

  /**
   * @param aManifestDir
   *        The <code>manifest</code> directory.
   * @param sSingleManifest
   *        If not <code>null</code>, only this file name is read.
   * @return All entries in manifest file name order and line order.
   * @throws IOException
   *         On read errors
   */
  @NonNull
  public static ICommonsList <SinabisEntry> readAll (@NonNull final Path aManifestDir,
                                                     @Nullable final String sSingleManifest) throws IOException
  {
    final List <Path> aFiles;
    try (final Stream <Path> aStream = Files.list (aManifestDir))
    {
      aFiles = aStream.filter (p -> p.getFileName ().toString ().endsWith (".jsonl"))
                      .filter (p -> sSingleManifest == null || p.getFileName ().toString ().equals (sSingleManifest))
                      .sorted ()
                      .toList ();
    }
    if (aFiles.isEmpty ())
      throw new IOException ("No matching *.jsonl manifest file found in '" + aManifestDir + "'");

    if (sSingleManifest == null)
      LOGGER.info ("Reading " + aFiles.size () + " JSONL files");

    final ICommonsList <SinabisEntry> ret = new CommonsArrayList <> ();
    for (final Path aFile : aFiles)
    {
      try (final BufferedReader aReader = Files.newBufferedReader (aFile, StandardCharsets.UTF_8))
      {
        // Read each line as a separate JSON
        String sLine;
        int nLineNum = 0;
        while ((sLine = aReader.readLine ()) != null)
        {
          nLineNum++;
          if (StringHelper.isEmpty (sLine.trim ()))
            continue;

          final IJsonObject aObj = JsonReader.builder ().source (sLine).readAsObject ();
          if (aObj == null)
            throw new IOException ("Failed to parse " + aFile.getFileName () + ":" + nLineNum + " as JSON object");

          final String sPDF = aObj.getAsString ("file");
          final String sXML = aObj.getAsString ("xml_file");
          if (StringHelper.isEmpty (sPDF) || StringHelper.isEmpty (sXML))
            throw new IOException ("Missing 'file' or 'xml_file' in " + aFile.getFileName () + ":" + nLineNum);
          ret.add (new SinabisEntry (aObj.getAsInt ("idx", -1), sPDF, sXML));
        }
      }
    }
    LOGGER.info ("Read " + ret.size () + " manifest entries from " + aFiles.size () + " file(s)");
    return ret;
  }
}
