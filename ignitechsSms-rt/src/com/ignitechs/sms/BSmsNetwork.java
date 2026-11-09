/*
 * Copyright (c) Ignitechs. All Rights Reserved.
 */
package com.ignitechs.sms;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.baja.alarm.AlarmDbConnection;
import javax.baja.alarm.BAlarmRecord;
import javax.baja.alarm.BAlarmService;
import javax.baja.driver.BDevice;
import javax.baja.driver.BDeviceNetwork;
import javax.baja.naming.SlotPath;
import javax.baja.sys.Action;
import javax.baja.sys.BAbsTime;
import javax.baja.sys.BBoolean;
import javax.baja.sys.BComponent;
import javax.baja.sys.BFacets;
import javax.baja.sys.BIcon;
import javax.baja.sys.BInteger;
import javax.baja.sys.BRelTime;
import javax.baja.sys.BStation;
import javax.baja.sys.Clock;
import javax.baja.sys.Context;
import javax.baja.sys.Flags;
import javax.baja.sys.Property;
import javax.baja.sys.Sys;
import javax.baja.sys.Type;
import javax.baja.util.BUuid;

import com.ignitechs.sms.core.AtModem;
import com.ignitechs.sms.core.GsmAlphabet;
import com.ignitechs.sms.core.OutgoingSms;
import com.ignitechs.sms.core.PhoneNumbers;
import com.ignitechs.sms.core.SmsDispatcher;
import com.ignitechs.sms.core.Templates;

/**
 * SMS Network: the driver root, placed under Drivers.
 *
 * Children:
 *  - gateways (devices): GSM modem (serial / TCP), SMPP, HTTP
 *  - Contacts: address book
 *  - Groups: named sets of contacts
 *  - Rules: point rules (value/state/status conditions) and senders
 *
 * All messages go through one queue with retry, gateway failover,
 * rate limiting and duplicate suppression.
 */
public class BSmsNetwork extends BDeviceNetwork
  implements SmsDispatcher.TransportProvider, SmsDispatcher.Listener
{
  // ------------------------------------------------------------------ address book & rules

  public static final Property contacts = newProperty(0, new BSmsContactFolder(), null);
  public BSmsContactFolder getContacts() { return (BSmsContactFolder) get(contacts); }

  public static final Property groups = newProperty(0, new BSmsGroupFolder(), null);
  public BSmsGroupFolder getGroups() { return (BSmsGroupFolder) get(groups); }

  public static final Property rules = newProperty(0, new BSmsRuleFolder(), null);
  public BSmsRuleFolder getRules() { return (BSmsRuleFolder) get(rules); }

  // ------------------------------------------------------------------ queue settings

  /** Retries after every gateway failed for a message. */
  public static final Property maxRetries = newProperty(0, 3, BFacets.make(BFacets.MIN, BInteger.make(0), BFacets.MAX, BInteger.make(20)));
  public int getMaxRetries() { return getInt(maxRetries); }
  public void setMaxRetries(int v) { setInt(maxRetries, v, null); }

  public static final Property retryDelay = newProperty(0, BRelTime.makeSeconds(60), null);
  public BRelTime getRetryDelay() { return (BRelTime) get(retryDelay); }
  public void setRetryDelay(BRelTime v) { set(retryDelay, v, null); }

  /** Safety limit against message storms; 0 = unlimited. Excess messages wait. */
  public static final Property maxMessagesPerHour = newProperty(0, 120, BFacets.make(BFacets.MIN, BInteger.make(0)));
  public int getMaxMessagesPerHour() { return getInt(maxMessagesPerHour); }
  public void setMaxMessagesPerHour(int v) { setInt(maxMessagesPerHour, v, null); }

  /** Identical text to the same number inside this window is sent only once. */
  public static final Property duplicateSuppression = newProperty(0, BRelTime.makeMinutes(2), null);
  public BRelTime getDuplicateSuppression() { return (BRelTime) get(duplicateSuppression); }
  public void setDuplicateSuppression(BRelTime v) { set(duplicateSuppression, v, null); }

  /** How often modem gateways check for incoming SMS commands; 0 = never. */
  public static final Property inboundPollInterval = newProperty(0, BRelTime.makeSeconds(30), null);
  public BRelTime getInboundPollInterval() { return (BRelTime) get(inboundPollInterval); }
  public void setInboundPollInterval(BRelTime v) { set(inboundPollInterval, v, null); }

  /** Longer messages are cut (each 153 GSM characters is one SMS part); 0 = unlimited. */
  public static final Property maxMessageLength = newProperty(0, 459, BFacets.make(BFacets.MIN, BInteger.make(0)));
  public int getMaxMessageLength() { return getInt(maxMessageLength); }
  public void setMaxMessageLength(int v) { setInt(maxMessageLength, v, null); }

  /** Replace smart quotes, dashes and the degree sign so messages stay in the 160-char GSM alphabet. */
  public static final Property gsmFriendlyText = newProperty(0, true, null);
  public boolean getGsmFriendlyText() { return getBoolean(gsmFriendlyText); }
  public void setGsmFriendlyText(boolean v) { setBoolean(gsmFriendlyText, v, null); }

  /** java.text.SimpleDateFormat pattern used by {time}. */
  public static final Property timeFormat = newProperty(0, "dd-MMM HH:mm", null);
  public String getTimeFormat() { return getString(timeFormat); }
  public void setTimeFormat(String v) { setString(timeFormat, v, null); }

  /** Name used for {station}; empty = the station name. */
  public static final Property siteName = newProperty(0, "", null);
  public String getSiteName() { return getString(siteName); }
  public void setSiteName(String v) { setString(siteName, v, null); }

  // ------------------------------------------------------------------ statistics

  public static final Property queueSize = newProperty(Flags.READONLY | Flags.TRANSIENT | Flags.SUMMARY, 0, null);
  public int getQueueSize() { return getInt(queueSize); }
  public void setQueueSize(int v) { setInt(queueSize, v, null); }

  public static final Property totalSent = newProperty(Flags.READONLY | Flags.SUMMARY, 0, null);
  public int getTotalSent() { return getInt(totalSent); }
  public void setTotalSent(int v) { setInt(totalSent, v, null); }

  public static final Property totalFailed = newProperty(Flags.READONLY, 0, null);
  public int getTotalFailed() { return getInt(totalFailed); }
  public void setTotalFailed(int v) { setInt(totalFailed, v, null); }

  public static final Property lastSentTime = newProperty(Flags.READONLY, BAbsTime.NULL, null);
  public BAbsTime getLastSentTime() { return (BAbsTime) get(lastSentTime); }
  public void setLastSentTime(BAbsTime v) { set(lastSentTime, v, null); }

  public static final Property lastError = newProperty(Flags.READONLY, "", null);
  public String getLastError() { return getString(lastError); }
  public void setLastError(String v) { setString(lastError, v, null); }

  /** Most recent activity, newest first (not saved). */
  public static final Property activity = newProperty(Flags.READONLY | Flags.TRANSIENT, "",
    BFacets.make(BFacets.MULTI_LINE, BBoolean.TRUE, BFacets.FIELD_WIDTH, BInteger.make(80)));
  public String getActivity() { return getString(activity); }
  public void setActivity(String v) { setString(activity, v, null); }

  // ------------------------------------------------------------------ actions

  public static final Action sendTestSms = newAction(0, new BSmsTestMessage(), null);
  public void sendTestSms(BSmsTestMessage arg) { invoke(sendTestSms, arg, null); }

  public static final Action clearQueue = newAction(Flags.CONFIRM_REQUIRED, null);
  public void clearQueue() { invoke(clearQueue, null, null); }

  public static final Action resetStatistics = newAction(0, null);
  public void resetStatistics() { invoke(resetStatistics, null, null); }

  /** Periodic rule scan (internal). */
  public static final Action scan = newAction(Flags.HIDDEN, null);
  public void scan() { invoke(scan, null, null); }

  public static final Action syncRecipients = newAction(Flags.HIDDEN, null);
  public void syncRecipients() { invoke(syncRecipients, null, null); }

  @Override
  public Type getType() { return TYPE; }
  public static final Type TYPE = Sys.loadType(BSmsNetwork.class);

  static final Logger LOG = Logger.getLogger("ignitechsSms");

  // ------------------------------------------------------------------ driver framework

  @Override
  public Type getDeviceType() { return BSmsGateway.TYPE; }

  @Override
  public Type getDeviceFolderType() { return BSmsGatewayFolder.TYPE; }

  @Override
  public boolean isChildLegal(BComponent child)
  {
    return child instanceof BSmsGateway || child instanceof BSmsGatewayFolder || !(child instanceof BDevice);
  }

  // ------------------------------------------------------------------ lifecycle

  private SmsDispatcher dispatcher;
  private Clock.Ticket ticket;
  private final LinkedList<String> activityLines = new LinkedList<>();

  @Override
  public void started() throws Exception
  {
    super.started();
    ticket = Clock.schedulePeriodically(this, BRelTime.makeSeconds(1), scan, null);
    restartDispatcher();
  }

  @Override
  public void descendantsStarted() throws Exception
  {
    super.descendantsStarted();
    syncAllRecipients();
  }

  @Override
  public void stopped() throws Exception
  {
    if (ticket != null) { ticket.cancel(); ticket = null; }
    stopDispatcher();
    super.stopped();
  }

  @Override
  public void changed(Property p, Context cx)
  {
    super.changed(p, cx);
    if (!isRunning()) return;
    if (p.getName().equals("enabled")) restartDispatcher();
    else if (p == maxRetries || p == retryDelay || p == maxMessagesPerHour || p == duplicateSuppression
             || p == inboundPollInterval)
    {
      SmsDispatcher d = dispatcher;
      if (d != null) d.setConfig(dispatcherConfig());
    }
  }

  private synchronized void restartDispatcher()
  {
    stopDispatcher();
    if (!getEnabled()) return;
    dispatcher = new SmsDispatcher(getName(), this, this, dispatcherConfig());
    dispatcher.start();
  }

  private synchronized void stopDispatcher()
  {
    if (dispatcher != null)
    {
      dispatcher.stop();
      dispatcher = null;
    }
    for (BSmsGateway g : getGatewayList()) g.closeConnection();
  }

  private SmsDispatcher.Config dispatcherConfig()
  {
    SmsDispatcher.Config c = new SmsDispatcher.Config();
    c.maxRetries = getMaxRetries();
    c.retryDelayMs = Math.max(1000, getRetryDelay().getMillis());
    c.maxPerHour = getMaxMessagesPerHour();
    c.duplicateWindowMs = getDuplicateSuppression().getMillis();
    c.inboundPollMs = getInboundPollInterval().getMillis();
    return c;
  }

  SmsDispatcher getDispatcher() { return dispatcher; }

  // ------------------------------------------------------------------ actions

  public void doScan()
  {
    long now = Clock.millis();
    scanRules(getRules(), now);
  }

  private void scanRules(BComponent folder, long now)
  {
    for (BComponent c : folder.getChildComponents())
    {
      if (c instanceof BSmsPointRule) ((BSmsPointRule) c).scan(now);
      else if (c instanceof BSmsRuleFolder) scanRules(c, now);
    }
  }

  public void doSendTestSms(BSmsTestMessage arg)
  {
    String phone = arg.getPhoneNumber();
    if (!PhoneNumbers.isValid(phone)) throw new IllegalArgumentException("Invalid phone number: " + phone);
    queueToPhone(phone, renderStandard(arg.getMessage()), "Test", PhoneNumbers.normalize(phone), 1);
  }

  public void doClearQueue()
  {
    SmsDispatcher d = dispatcher;
    if (d != null) d.clearQueue();
    setQueueSize(0);
    log("Queue cleared");
  }

  public void doResetStatistics()
  {
    setTotalSent(0);
    setTotalFailed(0);
    setLastError("");
    for (BSmsGateway g : getGatewayList()) g.resetStatistics();
  }

  public void doSyncRecipients() { syncAllRecipients(); }

  @Override
  public void doPing() throws Exception
  {
    for (BSmsGateway g : getGatewayList()) if (g.getEnabled()) g.ping();
    pingOk();
  }

  // ------------------------------------------------------------------ lookup

  /** Find the SMS network that contains c (c itself included). */
  public static BSmsNetwork findFor(BComponent c)
  {
    for (BComponent p = c; p != null; p = (BComponent) p.getParent())
    {
      if (p instanceof BSmsNetwork) return (BSmsNetwork) p;
      if (!(p.getParent() instanceof BComponent)) break;
    }
    return null;
  }

  /** First SMS network in the station (under Drivers), or null. */
  public static BSmsNetwork findInStation()
  {
    BStation station = Sys.getStation();
    if (station == null) return null;
    return search(station, 0);
  }

  private static BSmsNetwork search(BComponent c, int depth)
  {
    if (c instanceof BSmsNetwork) return (BSmsNetwork) c;
    if (depth > 3) return null;
    for (BComponent k : c.getChildComponents())
    {
      BSmsNetwork n = search(k, depth + 1);
      if (n != null) return n;
    }
    return null;
  }

  static void syncLater(BComponent c)
  {
    BSmsNetwork net = findFor(c);
    if (net != null && net.isRunning()) net.syncAllRecipients();
  }

  public List<BSmsContact> getContactList()
  {
    List<BSmsContact> out = new ArrayList<>();
    for (BComponent c : getContacts().getChildComponents())
      if (c instanceof BSmsContact) out.add((BSmsContact) c);
    return out;
  }

  public List<BSmsGroup> getGroupList()
  {
    List<BSmsGroup> out = new ArrayList<>();
    for (BComponent c : getGroups().getChildComponents())
      if (c instanceof BSmsGroup) out.add((BSmsGroup) c);
    return out;
  }

  /** Enabled gateways sorted by priority (1 = first). */
  public List<BSmsGateway> getGatewayList()
  {
    List<BSmsGateway> out = new ArrayList<>();
    collectGateways(this, out);
    Collections.sort(out, new Comparator<BSmsGateway>()
    {
      public int compare(BSmsGateway a, BSmsGateway b) { return Integer.compare(a.getPriority(), b.getPriority()); }
    });
    return out;
  }

  private static void collectGateways(BComponent c, List<BSmsGateway> out)
  {
    for (BComponent k : c.getChildComponents())
    {
      if (k instanceof BSmsGateway) out.add((BSmsGateway) k);
      else if (k instanceof BSmsGatewayFolder) collectGateways(k, out);
    }
  }

  // ------------------------------------------------------------------ recipients

  private final Set<BSmsRecipientList> externalLists =
    Collections.newSetFromMap(new WeakHashMap<BSmsRecipientList, Boolean>());

  /** Recipient lists outside the network (e.g. alarm recipients) register here. */
  public void registerExternal(BSmsRecipientList list)
  {
    synchronized (externalLists) { externalLists.add(list); }
    list.sync(this);
  }

  public void unregisterExternal(BSmsRecipientList list)
  {
    synchronized (externalLists) { externalLists.remove(list); }
  }

  public void syncAllRecipients()
  {
    syncTree(this);
    for (BSmsRecipientList l : externals()) l.sync(this);
  }

  private void syncTree(BComponent c)
  {
    for (BComponent k : c.getChildComponents())
    {
      if (k instanceof BSmsRecipientList) ((BSmsRecipientList) k).sync(this);
      else if (!(k instanceof BSmsContact) && !(k instanceof BSmsGateway)) syncTree(k);
    }
  }

  void recipientRenamed(boolean group, String oldName, String newName)
  {
    renameTree(this, group, oldName, newName);
    for (BSmsRecipientList l : externals()) l.renameEntry(group, oldName, newName);
  }

  private void renameTree(BComponent c, boolean group, String oldName, String newName)
  {
    for (BComponent k : c.getChildComponents())
    {
      if (k instanceof BSmsRecipientList) ((BSmsRecipientList) k).renameEntry(group, oldName, newName);
      else if (!(k instanceof BSmsContact) && !(k instanceof BSmsGateway)) renameTree(k, group, oldName, newName);
    }
  }

  private List<BSmsRecipientList> externals()
  {
    synchronized (externalLists) { return new ArrayList<>(externalLists); }
  }

  /**
   * Expand a checklist into the contacts that should get a message now:
   * selected contacts plus members of selected groups, enabled and on duty,
   * de-duplicated by phone number.
   */
  public List<BSmsContact> resolve(BSmsRecipientList list)
  {
    Map<String, BSmsContact> byName = new HashMap<>();
    for (BSmsContact c : getContactList()) byName.put(c.getName(), c);
    Map<String, BSmsGroup> groupsByName = new HashMap<>();
    for (BSmsGroup g : getGroupList()) groupsByName.put(g.getName(), g);

    List<String> names = new ArrayList<>(list.getSelectedContacts());
    for (String gn : list.getSelectedGroups())
    {
      BSmsGroup g = groupsByName.get(gn);
      if (g != null && g.getEnabled()) names.addAll(g.getMembers().getSelectedContacts());
    }

    Map<String, BSmsContact> byPhone = new LinkedHashMap<>();
    for (String n : names)
    {
      BSmsContact c = byName.get(n);
      if (c == null || !c.isReachableNow()) continue;
      String key = PhoneNumbers.normalize(c.getPhoneNumber());
      if (!byPhone.containsKey(key)) byPhone.put(key, c);
    }
    return new ArrayList<>(byPhone.values());
  }

  // ------------------------------------------------------------------ queueing

  /** Queue text for every resolved contact; returns how many were queued. */
  public int queueToRecipients(BSmsRecipientList list, String text, String origin, int priority)
  {
    List<BSmsContact> targets = resolve(list);
    if (targets.isEmpty())
    {
      log(origin + ": no reachable recipients (" + list.summary() + ")");
      return 0;
    }
    int n = 0;
    for (BSmsContact c : targets)
      if (queueToPhone(c.getPhoneNumber(), text, origin, c.getContactName(), priority)) n++;
    return n;
  }

  public boolean queueToPhone(String phone, String text, String origin, String recipient, int priority)
  {
    SmsDispatcher d = dispatcher;
    if (d == null)
    {
      log(origin + ": SMS network disabled, message to " + recipient + " not sent");
      return false;
    }
    String msg = prepareText(text);
    boolean queued = d.enqueue(new OutgoingSms(PhoneNumbers.normalize(phone), msg, origin, recipient, priority));
    if (!queued) log(origin + ": suppressed duplicate / queue full for " + recipient);
    return queued;
  }

  String prepareText(String text)
  {
    String t = text == null ? "" : text.trim();
    if (getGsmFriendlyText()) t = GsmAlphabet.simplify(t);
    return Templates.limit(t, getMaxMessageLength());
  }

  // ------------------------------------------------------------------ templates

  public String getSiteDisplayName()
  {
    String s = getSiteName();
    if (s != null && !s.trim().isEmpty()) return s.trim();
    BStation st = Sys.getStation();
    return st == null ? "Niagara" : st.getStationName();
  }

  public String formatTime(long millis)
  {
    try { return new SimpleDateFormat(getTimeFormat(), Locale.ENGLISH).format(new Date(millis)); }
    catch (Exception e) { return new SimpleDateFormat("dd-MMM HH:mm", Locale.ENGLISH).format(new Date(millis)); }
  }

  /** Values available in every template: {station} {time}. */
  public Map<String, String> standardValues()
  {
    Map<String, String> v = new HashMap<>();
    v.put("station", getSiteDisplayName());
    v.put("time", formatTime(System.currentTimeMillis()));
    return v;
  }

  public String renderStandard(String template)
  {
    return Templates.render(template, standardValues());
  }

  // ------------------------------------------------------------------ alarm ack codes

  private final LinkedHashMap<String, BUuid> ackCodes = new LinkedHashMap<String, BUuid>(64, 0.75f, false)
  {
    private static final long serialVersionUID = 1L;
    @Override protected boolean removeEldestEntry(Map.Entry<String, BUuid> e) { return size() > 1000; }
  };
  private int nextAckCode = 100 + (int) (System.currentTimeMillis() % 800);

  /** Short numeric code a recipient can reply with ("ACK 123") to acknowledge. */
  public synchronized String registerAlarm(BUuid uuid)
  {
    for (Map.Entry<String, BUuid> e : ackCodes.entrySet())
      if (e.getValue().equals(uuid)) return e.getKey();
    nextAckCode = nextAckCode >= 9999 ? 100 : nextAckCode + 1;
    String code = Integer.toString(nextAckCode);
    ackCodes.put(code, uuid);
    return code;
  }

  private synchronized BUuid lookupAck(String code) { return ackCodes.get(code); }

  // ------------------------------------------------------------------ inbound commands

  void handleInbound(String via, AtModem.Inbound in)
  {
    String text = in.text == null ? "" : in.text.trim();
    BSmsContact from = null;
    for (BSmsContact c : getContactList())
      if (PhoneNumbers.sameNumber(c.getPhoneNumber(), in.from)) { from = c; break; }

    log("Received from " + (from == null ? in.from : from.getContactName()) + " via " + via + ": " + text);
    if (from == null || !from.getAllowSmsCommands() || !from.getEnabled())
    {
      LOG.info("Ignored SMS from " + in.from + " (not a contact with SMS commands allowed)");
      return;
    }

    String[] words = text.split("\\s+");
    String cmd = words.length == 0 ? "" : words[0].toUpperCase(Locale.ENGLISH);
    String reply;
    switch (cmd)
    {
      case "ACK":
      case "A":
        reply = words.length < 2 ? "Usage: ACK <code>" : ackAlarm(words[1], from.getContactName());
        break;
      case "STATUS":
      case "S":
        reply = statusSummary();
        break;
      default:
        reply = "Commands: ACK <code> - acknowledge alarm; STATUS - active SMS rules";
    }
    queueToPhone(from.getPhoneNumber(), "[" + getSiteDisplayName() + "] " + reply, "Reply", from.getContactName(), 1);
  }

  private String ackAlarm(String code, String user)
  {
    BUuid uuid = lookupAck(code.trim());
    if (uuid == null) return "Unknown alarm code " + code;
    try
    {
      BAlarmService svc = (BAlarmService) Sys.getService(BAlarmService.TYPE);
      AlarmDbConnection conn = svc.getAlarmDb().getDbConnection(null);
      BAlarmRecord rec;
      try { rec = conn.getRecord(uuid); }
      finally { conn.close(); }
      if (rec == null) return "Alarm " + code + " not found";
      if (rec.isAcknowledged()) return "Alarm " + code + " already acknowledged";
      rec.ackAlarm("SMS:" + user);
      svc.ackAlarm(rec);
      log("Alarm " + code + " acknowledged by " + user + " via SMS");
      return "Alarm " + code + " acknowledged";
    }
    catch (Exception e)
    {
      LOG.log(Level.WARNING, "SMS acknowledgement failed", e);
      return "Could not acknowledge alarm " + code + ": " + e.getMessage();
    }
  }

  private String statusSummary()
  {
    List<String> active = new ArrayList<>();
    collectActive(getRules(), active);
    if (active.isEmpty()) return "No active SMS rules.";
    StringBuilder sb = new StringBuilder(active.size() + " active: ");
    for (int i = 0; i < active.size(); i++) { if (i > 0) sb.append("; "); sb.append(active.get(i)); }
    return sb.toString();
  }

  private void collectActive(BComponent folder, List<String> out)
  {
    for (BComponent c : folder.getChildComponents())
    {
      if (c instanceof BSmsPointRule && ((BSmsPointRule) c).getActive())
        out.add(SlotPath.unescape(c.getName()) + "=" + ((BSmsPointRule) c).getCurrentValue());
      else if (c instanceof BSmsRuleFolder) collectActive(c, out);
    }
  }

  // ------------------------------------------------------------------ TransportProvider

  @Override
  public List<SmsDispatcher.Transport> transports()
  {
    List<SmsDispatcher.Transport> up = new ArrayList<>();
    List<SmsDispatcher.Transport> down = new ArrayList<>();
    for (BSmsGateway g : getGatewayList())
    {
      if (!g.getEnabled() || g.getStatus().isDisabled()) continue;
      if (g.getStatus().isDown() || g.getStatus().isFault()) down.add(g);
      else up.add(g);
    }
    up.addAll(down);   // gateways that failed their last check are still tried, but last
    return up;
  }

  // ------------------------------------------------------------------ Listener

  @Override
  public void onSent(OutgoingSms m, SmsDispatcher.Transport via)
  {
    setTotalSent(getTotalSent() + 1);
    setLastSentTime(BAbsTime.make());
    for (BSmsContact c : getContactList())
      if (c.getContactName().equals(m.recipient))
      {
        c.setMessagesSent(c.getMessagesSent() + 1);
        c.setLastMessageTime(BAbsTime.make());
      }
    log("Sent to " + m.recipient + " via " + via.transportName() + " [" + m.origin + "]: " + m.text);
  }

  @Override
  public void onAttemptFailed(SmsDispatcher.Transport via, OutgoingSms m, Exception error)
  {
    log("Gateway " + via.transportName() + " failed for " + m.recipient + ": " + error.getMessage());
  }

  @Override
  public void onFailed(OutgoingSms m, String reason, boolean willRetry)
  {
    setLastError(reason == null ? "" : reason);
    if (!willRetry)
    {
      setTotalFailed(getTotalFailed() + 1);
      log("FAILED to " + m.recipient + " after " + m.attempts + " attempt(s): " + reason);
      LOG.warning("SMS to " + m.recipient + " failed: " + reason);
    }
    else log("Will retry message to " + m.recipient + ": " + reason);
  }

  @Override
  public void onInbound(SmsDispatcher.Transport via, AtModem.Inbound msg)
  {
    handleInbound(via.transportName(), msg);
  }

  @Override
  public void onQueueChanged(int size)
  {
    if (getQueueSize() != size) setQueueSize(size);
  }

  // ------------------------------------------------------------------ activity log

  void log(String line)
  {
    String entry = formatTime(System.currentTimeMillis()) + "  " + line;
    LOG.fine(entry);
    String joined;
    synchronized (activityLines)
    {
      activityLines.addFirst(entry);
      while (activityLines.size() > 50) activityLines.removeLast();
      StringBuilder sb = new StringBuilder();
      for (String s : activityLines) sb.append(s).append('\n');
      joined = sb.toString();
    }
    if (isRunning()) setActivity(joined);
  }

  @Override
  public BIcon getIcon() { return ICON; }
  private static final BIcon ICON = BIcon.std("mobile.png");
}
