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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.NonNull;

/**
 * The element paths, occurrence counts and leaf values of one CII invoice. Paths are made of local
 * names only, separated by <code>/</code>, without the root element - e.g.
 * <code>ExchangedDocument/TypeCode</code>. Attributes are addressed as <code>path@attribute</code>.
 *
 * @author Philip Helger
 */
public final class InvoiceData
{
  private final Map <String, Integer> m_aCounts = new HashMap <> ();
  private final Map <String, List <String>> m_aValues = new HashMap <> ();

  void addElement (@NonNull final String sPath)
  {
    m_aCounts.merge (sPath, Integer.valueOf (1), Integer::sum);
  }

  void addValue (@NonNull final String sPath, @NonNull final String sValue)
  {
    m_aValues.computeIfAbsent (sPath, _ -> new ArrayList <> ()).add (sValue);
  }

  /**
   * @param sPath
   *        Path to check
   * @return The number of elements (or attributes) at that path
   */
  public int count (@NonNull final String sPath)
  {
    final Integer ret = m_aCounts.get (sPath);
    return ret == null ? 0 : ret.intValue ();
  }

  public boolean has (@NonNull final String sPath)
  {
    return m_aCounts.containsKey (sPath);
  }

  /**
   * @param sPath
   *        Path to check
   * @return All non-empty leaf values at that path in document order. Never <code>null</code>.
   */
  @NonNull
  public List <String> values (@NonNull final String sPath)
  {
    final List <String> ret = m_aValues.get (sPath);
    return ret == null ? List.of () : ret;
  }

  public boolean hasValue (@NonNull final String sPath, @NonNull final String sValue)
  {
    return values (sPath).contains (sValue);
  }

  /**
   * @param sPath
   *        Path to check
   * @return All values at that path that are numbers. Others are ignored.
   */
  @NonNull
  public List <BigDecimal> numbers (@NonNull final String sPath)
  {
    final List <BigDecimal> ret = new ArrayList <> ();
    for (final String s : values (sPath))
      try
      {
        ret.add (new BigDecimal (s));
      }
      catch (final NumberFormatException ex)
      {
        // ignore
      }
    return ret;
  }
}
