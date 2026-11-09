/*
 * Copyright (c) Ignitechs. All Rights Reserved.
 */
package com.ignitechs.sms.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Outbound SMS queue with a single worker thread.
 *
 *  - gateways are tried in priority order (automatic failover)
 *  - failed messages are retried after a delay, up to maxRetries
 *  - optional hourly rate limit (messages wait, they are not dropped)
 *  - identical messages to the same number within the duplicate window are suppressed
 *  - exclusive jobs (tests, pings) and inbound polling run on the same thread
 *    so a modem port is never used concurrently
 */
public final class SmsDispatcher
{
  public interface Transport
  {
    String transportName();
    void send(String phone, String text) throws Exception;
    /** Poll for received messages; empty when unsupported. */
    List<AtModem.Inbound> pollInbound() throws Exception;
    boolean supportsInbound();
  }

  public interface TransportProvider
  {
    /** Usable transports in the order they should be tried. */
    List<Transport> transports();
  }

  public interface Listener
  {
    void onSent(OutgoingSms m, Transport via);
    void onAttemptFailed(Transport via, OutgoingSms m, Exception error);
    void onFailed(OutgoingSms m, String reason, boolean willRetry);
    void onInbound(Transport via, AtModem.Inbound msg);
    void onQueueChanged(int size);
  }

  public static final class Config
  {
    public int maxRetries = 3;
    public long retryDelayMs = 60000;
    public int maxPerHour;               // 0 = unlimited
    public long duplicateWindowMs = 60000;
    public long inboundPollMs = 30000;   // 0 = disabled
    public int maxQueue = 1000;
  }

  private static final Logger LOG = Logger.getLogger("ignitechsSms.dispatcher");

  private final String name;
  private final TransportProvider provider;
  private final Listener listener;
  private volatile Config config;

  private final List<OutgoingSms> queue = new ArrayList<>();
  private final Deque<Runnable> jobs = new ArrayDeque<>();
  private final Deque<Long> sentTimes = new ArrayDeque<>();
  private final Map<String, Long> recent = new HashMap<>();
  private Thread thread;
  private volatile boolean running;
  private long lastInboundPoll;
  private int refCounter = (int) (System.currentTimeMillis() & 0xFF);

  public SmsDispatcher(String name, TransportProvider provider, Listener listener, Config config)
  {
    this.name = name;
    this.provider = provider;
    this.listener = listener;
    this.config = config;
  }

  public void setConfig(Config c) { this.config = c; }

  public synchronized void start()
  {
    if (running) return;
    running = true;
    thread = new Thread(this::run, "SmsDispatcher:" + name);
    thread.setDaemon(true);
    thread.start();
  }

  public void stop()
  {
    Thread t;
    synchronized (this)
    {
      running = false;
      t = thread;
      thread = null;
      notifyAll();
    }
    if (t != null)
    {
      t.interrupt();
      try { t.join(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
  }

  public boolean isRunning() { return running; }

  public synchronized int queueSize() { return queue.size(); }

  /**
   * Queue a message. Returns false when it was suppressed as a duplicate or
   * the queue is full.
   */
  public boolean enqueue(OutgoingSms m)
  {
    int size;
    synchronized (this)
    {
      Config c = config;
      long now = System.currentTimeMillis();
      if (c.duplicateWindowMs > 0)
      {
        String key = PhoneNumbers.normalize(m.phone) + "\u0000" + m.text;
        Long last = recent.get(key);
        if (last != null && now - last < c.duplicateWindowMs)
        {
          LOG.fine("Suppressed duplicate SMS to " + m.phone);
          return false;
        }
        recent.put(key, now);
        if (recent.size() > 2000) pruneRecent(now, c.duplicateWindowMs);
      }
      if (queue.size() >= c.maxQueue)
      {
        LOG.warning("SMS queue full (" + c.maxQueue + "), message to " + m.phone + " dropped");
        return false;
      }
      queue.add(m);
      size = queue.size();
      notifyAll();
    }
    listener.onQueueChanged(size);
    return true;
  }

  /** Run a job on the dispatcher thread (e.g. a gateway test). */
  public synchronized void runExclusive(Runnable job)
  {
    jobs.add(job);
    notifyAll();
  }

  public synchronized void clearQueue()
  {
    queue.clear();
    notifyAll();
  }

  /** Next reference number for concatenated messages. */
  public synchronized int nextRef()
  {
    refCounter = (refCounter + 1) & 0xFF;
    return refCounter;
  }

  private void pruneRecent(long now, long window)
  {
    for (Iterator<Map.Entry<String, Long>> it = recent.entrySet().iterator(); it.hasNext(); )
      if (now - it.next().getValue() >= window) it.remove();
  }

  // ------------------------------------------------------------------ worker

  private void run()
  {
    LOG.fine("Dispatcher " + name + " started");
    while (running)
    {
      try
      {
        Runnable job = null;
        OutgoingSms next = null;
        synchronized (this)
        {
          long now = System.currentTimeMillis();
          job = jobs.poll();
          if (job == null)
          {
            next = nextDue(now);
            if (next == null && !inboundDue(now))
            {
              long wait = waitTime(now);
              wait(Math.max(50, Math.min(wait, 1000)));
              continue;
            }
          }
        }
        if (job != null) { runSafely(job); continue; }
        if (next != null) { deliver(next); continue; }
        pollInbound();
      }
      catch (InterruptedException e)
      {
        if (!running) break;
      }
      catch (Throwable t)
      {
        LOG.log(Level.SEVERE, "Dispatcher error", t);
      }
    }
    LOG.fine("Dispatcher " + name + " stopped");
  }

  /** Remove and return the next due message, honouring the rate limit. Caller holds lock. */
  private OutgoingSms nextDue(long now)
  {
    if (queue.isEmpty()) return null;
    Config c = config;
    if (c.maxPerHour > 0)
    {
      while (!sentTimes.isEmpty() && now - sentTimes.peekFirst() > 3600000L) sentTimes.pollFirst();
      if (sentTimes.size() >= c.maxPerHour) return null;
    }
    OutgoingSms best = null;
    for (OutgoingSms m : queue)
      if (m.notBefore <= now && (best == null || m.priority < best.priority
          || (m.priority == best.priority && m.created < best.created)))
        best = m;
    if (best != null) queue.remove(best);
    return best;
  }

  private long waitTime(long now)
  {
    long wait = 1000;
    for (OutgoingSms m : queue) wait = Math.min(wait, m.notBefore - now);
    return wait;
  }

  private boolean inboundDue(long now)
  {
    long p = config.inboundPollMs;
    return p > 0 && now - lastInboundPoll >= p;
  }

  private void deliver(OutgoingSms m)
  {
    List<Transport> list;
    try { list = provider.transports(); }
    catch (Exception e) { list = Collections.emptyList(); }

    String lastError = list.isEmpty() ? "no enabled gateway" : null;
    for (Transport t : list)
    {
      if (!running) break;
      try
      {
        t.send(m.phone, m.text);
        synchronized (this) { sentTimes.addLast(System.currentTimeMillis()); }
        m.sentVia = t.transportName();
        listener.onSent(m, t);
        listener.onQueueChanged(queueSize());
        return;
      }
      catch (Exception e)
      {
        lastError = t.transportName() + ": " + (e.getMessage() == null ? e.toString() : e.getMessage());
        LOG.log(Level.FINE, "Send via " + t.transportName() + " failed", e);
        listener.onAttemptFailed(t, m, e);
      }
    }

    m.attempts++;
    m.lastError = lastError;
    Config c = config;
    boolean retry = running && m.attempts <= c.maxRetries;
    if (retry)
    {
      m.notBefore = System.currentTimeMillis() + c.retryDelayMs;
      synchronized (this) { queue.add(m); }
    }
    listener.onFailed(m, lastError, retry);
    listener.onQueueChanged(queueSize());
  }

  private void pollInbound()
  {
    lastInboundPoll = System.currentTimeMillis();
    List<Transport> list;
    try { list = provider.transports(); }
    catch (Exception e) { return; }
    for (Transport t : list)
    {
      if (!t.supportsInbound()) continue;
      try
      {
        for (AtModem.Inbound in : t.pollInbound()) listener.onInbound(t, in);
      }
      catch (Exception e)
      {
        LOG.log(Level.FINE, "Inbound poll via " + t.transportName() + " failed", e);
      }
    }
  }

  private static void runSafely(Runnable r)
  {
    try { r.run(); }
    catch (Throwable t) { LOG.log(Level.WARNING, "SMS job failed", t); }
  }
}
