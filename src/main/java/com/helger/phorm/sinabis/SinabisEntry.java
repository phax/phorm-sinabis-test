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

import org.jspecify.annotations.NonNull;

/**
 * A single line of a Sinabis manifest file.
 *
 * @author Philip Helger
 */
public final class SinabisEntry
{
  private final int m_nIdx;
  private final String m_sPDFFile;
  private final String m_sXMLFile;

  public SinabisEntry (final int nIdx, @NonNull final String sPDFFile, @NonNull final String sXMLFile)
  {
    m_nIdx = nIdx;
    m_sPDFFile = sPDFFile;
    m_sXMLFile = sXMLFile;
  }

  public int getIdx ()
  {
    return m_nIdx;
  }

  /**
   * @return The path of the PDF file, relative to the dataset root.
   */
  @NonNull
  public String getPDFFile ()
  {
    return m_sPDFFile;
  }

  /**
   * @return The path of the XML file, relative to the dataset root.
   */
  @NonNull
  public String getXMLFile ()
  {
    return m_sXMLFile;
  }

  @NonNull
  public String getFile (@NonNull final ECallKind eKind)
  {
    return eKind == ECallKind.PDF ? m_sPDFFile : m_sXMLFile;
  }
}
