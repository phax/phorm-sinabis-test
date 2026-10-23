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
 * The phorm API used for a single call.
 *
 * @author Philip Helger
 */
public enum ECallKind
{
  /** The plain XML via <code>/api/dd_and_validate</code> */
  XML ("xml", "/api/dd_and_validate"),
  /** The hybrid PDF via <code>/api/hybrid_validate</code> */
  PDF ("pdf", "/api/hybrid_validate");

  private final String m_sID;
  private final String m_sAPI;

  ECallKind (@NonNull final String sID, @NonNull final String sAPI)
  {
    m_sID = sID;
    m_sAPI = sAPI;
  }

  @NonNull
  public String getID ()
  {
    return m_sID;
  }

  @NonNull
  public String getAPI ()
  {
    return m_sAPI;
  }

  public boolean isXml ()
  {
    return this == XML;
  }
}
