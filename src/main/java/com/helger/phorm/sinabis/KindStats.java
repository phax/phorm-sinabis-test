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

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import com.helger.json.IJsonObject;
import com.helger.json.JsonObject;

/**
 * Thread safe statistics of all calls of one {@link ECallKind}.
 *
 * @author Philip Helger
 */
public final class KindStats
{
  /** The outcome of a single call */
  public enum EOutcome
  {
    /** phorm validated the document and found it valid */
    VALID,
    /** phorm validated the document and found it invalid - a mismatch to the Sinabis claim */
    INVALID,
    /** phorm produced no verdict (transport, HTTP or protocol problem) */
    CALL_ERROR,
    /** The local file could not be read */
    FILE_ERROR
  }

  private final ECallKind m_eKind;
  private final AtomicLong m_aValid = new AtomicLong ();
  private final AtomicLong m_aInvalid = new AtomicLong ();
  private final AtomicLong m_aCallError = new AtomicLong ();
  private final AtomicLong m_aFileError = new AtomicLong ();
  private final AtomicLong m_aBytes = new AtomicLong ();
  private final Map <String, AtomicLong> m_aErrorIDs = new ConcurrentHashMap <> ();
  private final Map <String, AtomicLong> m_aCallErrorTypes = new ConcurrentHashMap <> ();
  private final Map <String, AtomicLong> m_aVESIDs = new ConcurrentHashMap <> ();

  // Latencies of calls that yielded a verdict (VALID + INVALID)
  private final long [] m_aClientMillis;
  private final long [] m_aServerMillis;
  private int m_nLatencyCount = 0;

  public KindStats (@NonNull final ECallKind eKind, final int nMaxCalls)
  {
    m_eKind = eKind;
    m_aClientMillis = new long [nMaxCalls];
    m_aServerMillis = new long [nMaxCalls];
  }

  @NonNull
  public ECallKind getKind ()
  {
    return m_eKind;
  }

  private static void _inc (final Map <String, AtomicLong> aMap, final String sKey)
  {
    aMap.computeIfAbsent (sKey, _ -> new AtomicLong ()).incrementAndGet ();
  }

  public void addFileError ()
  {
    m_aFileError.incrementAndGet ();
  }

  public void addCallError (@NonNull final String sErrorType)
  {
    m_aCallError.incrementAndGet ();
    _inc (m_aCallErrorTypes, sErrorType);
  }

  /**
   * Record a call that yielded a verdict.
   *
   * @param bValid
   *        <code>true</code> if phorm found the document valid
   * @param nBytes
   *        Payload size
   * @param nClientMillis
   *        Wall clock time of the call incl. network and client side response parsing
   * @param nServerMillis
   *        Duration reported by phorm, or -1
   * @param aErrorIDs
   *        IDs of all findings of level ERROR and above
   * @param sVESID
   *        The determined VESID. May be <code>null</code>.
   */
  public void addVerdict (final boolean bValid,
                          final long nBytes,
                          final long nClientMillis,
                          final long nServerMillis,
                          @NonNull final Iterable <String> aErrorIDs,
                          @Nullable final String sVESID)
  {
    (bValid ? m_aValid : m_aInvalid).incrementAndGet ();
    m_aBytes.addAndGet (nBytes);
    for (final String sErrorID : aErrorIDs)
      _inc (m_aErrorIDs, sErrorID);
    _inc (m_aVESIDs, sVESID == null ? "(none)" : sVESID);
    synchronized (this)
    {
      m_aClientMillis[m_nLatencyCount] = nClientMillis;
      m_aServerMillis[m_nLatencyCount] = nServerMillis;
      m_nLatencyCount++;
    }
  }

  public long getTotalCount ()
  {
    return m_aValid.get () + m_aInvalid.get () + m_aCallError.get () + m_aFileError.get ();
  }

  private static double _percentile (final long [] aSorted, final double dPercent)
  {
    if (aSorted.length == 0)
      return Double.NaN;
    // Nearest-rank method
    final int nIndex = (int) Math.ceil (dPercent / 100d * aSorted.length) - 1;
    return aSorted[Math.max (0, Math.min (aSorted.length - 1, nIndex))];
  }

  @NonNull
  private static IJsonObject _latency (final long [] aValues)
  {
    final long [] aSorted = aValues.clone ();
    Arrays.sort (aSorted);
    final IJsonObject ret = new JsonObject ();
    ret.add ("count", aSorted.length);
    if (aSorted.length > 0)
    {
      double dSum = 0;
      for (final long n : aSorted)
        dSum += n;
      ret.add ("avg_ms", dSum / aSorted.length);
      ret.add ("min_ms", aSorted[0]);
      ret.add ("median_ms", _percentile (aSorted, 50));
      ret.add ("p90_ms", _percentile (aSorted, 90));
      ret.add ("p95_ms", _percentile (aSorted, 95));
      ret.add ("p99_ms", _percentile (aSorted, 99));
      ret.add ("max_ms", aSorted[aSorted.length - 1]);
    }
    return ret;
  }

  @NonNull
  private static IJsonObject _counts (final Map <String, AtomicLong> aMap)
  {
    // Sorted by count descending
    final IJsonObject ret = new JsonObject ();
    aMap.entrySet ()
        .stream ()
        .sorted ((a, b) -> Long.compare (b.getValue ().get (), a.getValue ().get ()))
        .forEach (e -> ret.add (e.getKey (), e.getValue ().get ()));
    return ret;
  }

  /**
   * @param nWallMillis
   *        Wall clock duration of the measured phase
   * @return The statistics as JSON
   */
  @NonNull
  public synchronized IJsonObject getAsJson (final long nWallMillis)
  {
    final long nVerdicts = m_aValid.get () + m_aInvalid.get ();
    final IJsonObject ret = new JsonObject ();
    ret.add ("api", m_eKind.getAPI ());
    ret.add ("total", getTotalCount ());
    ret.add ("valid", m_aValid.get ());
    ret.add ("invalid", m_aInvalid.get ());
    ret.add ("call_error", m_aCallError.get ());
    ret.add ("file_error", m_aFileError.get ());
    ret.add ("invalid_percent", nVerdicts == 0 ? 0d : 100d * m_aInvalid.get () / nVerdicts);
    ret.add ("payload_bytes", m_aBytes.get ());
    ret.add ("throughput_per_second", nWallMillis <= 0 ? 0d : nVerdicts * 1000d / nWallMillis);
    ret.add ("client_latency", _latency (Arrays.copyOf (m_aClientMillis, m_nLatencyCount)));
    // -1 means "not reported by the server"
    ret.add ("server_latency",
             _latency (Arrays.stream (m_aServerMillis, 0, m_nLatencyCount).filter (n -> n >= 0).toArray ()));
    ret.add ("error_ids", _counts (m_aErrorIDs));
    ret.add ("call_error_types", _counts (m_aCallErrorTypes));
    ret.add ("vesids", _counts (m_aVESIDs));
    return ret;
  }
}
