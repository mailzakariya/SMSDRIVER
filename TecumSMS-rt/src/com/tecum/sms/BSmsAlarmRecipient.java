/*
 * Copyright (c) Tecum. All Rights Reserved.
 */
package com.tecum.sms;

import java.util.Map;
import java.util.logging.Level;

import javax.baja.alarm.BAlarmRecipient;
import javax.baja.alarm.BAlarmRecord;
import javax.baja.naming.BOrd;
import javax.baja.sys.BAbsTime;
import javax.baja.sys.BFacets;
import javax.baja.sys.BIcon;
import javax.baja.sys.BInteger;
import javax.baja.sys.BObject;
import javax.baja.sys.Context;
import javax.baja.sys.Flags;
import javax.baja.sys.Property;
import javax.baja.sys.Sys;
import javax.baja.sys.Type;
import javax.baja.util.BFormat;

import com.tecum.sms.core.Templates;

/**
 * Alarm recipient that forwards Niagara alarms as SMS. Place it in the
 * AlarmService and link alarm classes to its "routeAlarm" action, exactly
 * like the standard Email/Console recipients. The inherited time range,
 * days of week and transitions filter which alarms are sent.
 *
 * Placeholders: {station} {time} {source} {msg} {state} {alarmClass}
 * {priority} {value} {ackCode} {ackHint} {user}
 */
public class BSmsAlarmRecipient extends BAlarmRecipient
{
  /** SMS network to use; leave empty to use the first one in the station. */
  public static final Property smsNetwork = newProperty(0, BOrd.NULL, null);
  public BOrd getSmsNetwork() { return (BOrd) get(smsNetwork); }
  public void setSmsNetwork(BOrd v) { set(smsNetwork, v, null); }

  public static final Property recipients = newProperty(0, new BSmsRecipientList(), null);
  public BSmsRecipientList getRecipients() { return (BSmsRecipientList) get(recipients); }
  public void setRecipients(BSmsRecipientList v) { set(recipients, v, null); }

  public static final Property alarmText = newProperty(0, "{station} {state}: {source} - {msg} [{alarmClass}] {time}{ackHint}",
    BFacets.make(BFacets.FIELD_WIDTH, BInteger.make(70)));
  public String getAlarmText() { return getString(alarmText); }
  public void setAlarmText(String v) { setString(alarmText, v, null); }

  /** Used for acknowledgement notifications (when Route Acks is true). */
  public static final Property ackText = newProperty(0, "{station}: {source} acknowledged by {user} {time}",
    BFacets.make(BFacets.FIELD_WIDTH, BInteger.make(70)));
  public String getAckText() { return getString(ackText); }
  public void setAckText(String v) { setString(ackText, v, null); }

  /** Append "Reply ACK &lt;code&gt;" so recipients can acknowledge from their phone (needs a modem gateway). */
  public static final Property includeAckCode = newProperty(0, true, null);
  public boolean getIncludeAckCode() { return getBoolean(includeAckCode); }
  public void setIncludeAckCode(boolean v) { setBoolean(includeAckCode, v, null); }

  public static final Property alarmsForwarded = newProperty(Flags.READONLY, 0, null);
  public int getAlarmsForwarded() { return getInt(alarmsForwarded); }
  public void setAlarmsForwarded(int v) { setInt(alarmsForwarded, v, null); }

  public static final Property lastAlarm = newProperty(Flags.READONLY, "", null);
  public String getLastAlarm() { return getString(lastAlarm); }
  public void setLastAlarm(String v) { setString(lastAlarm, v, null); }

  public static final Property lastAlarmTime = newProperty(Flags.READONLY, BAbsTime.NULL, null);
  public BAbsTime getLastAlarmTime() { return (BAbsTime) get(lastAlarmTime); }
  public void setLastAlarmTime(BAbsTime v) { set(lastAlarmTime, v, null); }

  @Override
  public Type getType() { return TYPE; }
  public static final Type TYPE = Sys.loadType(BSmsAlarmRecipient.class);

  private BSmsNetwork registeredWith;

  @Override
  public void started() throws Exception
  {
    super.started();
    attach();
  }

  @Override
  public void stopped() throws Exception
  {
    detach();
    super.stopped();
  }

  @Override
  public void changed(Property p, Context cx)
  {
    super.changed(p, cx);
    if (p == smsNetwork && isRunning()) { detach(); attach(); }
  }

  private BSmsNetwork attach()
  {
    BSmsNetwork net = network();
    if (net != null && net != registeredWith)
    {
      detach();
      net.registerExternal(getRecipients());
      registeredWith = net;
    }
    return net;
  }

  private void detach()
  {
    if (registeredWith != null) registeredWith.unregisterExternal(getRecipients());
    registeredWith = null;
  }

  private BSmsNetwork network()
  {
    BOrd ord = getSmsNetwork();
    if (ord != null && !ord.isNull())
    {
      try
      {
        BObject o = ord.get(this);
        if (o instanceof BSmsNetwork) return (BSmsNetwork) o;
      }
      catch (Exception e)
      {
        BSmsNetwork.LOG.log(Level.FINE, "Cannot resolve SMS network " + ord, e);
      }
      return null;
    }
    return BSmsNetwork.findInStation();
  }

  // ------------------------------------------------------------------ BAlarmRecipient

  @Override
  public void handleAlarm(BAlarmRecord rec)
  {
    BSmsNetwork net = attach();
    if (net == null)
    {
      BSmsNetwork.LOG.warning(getName() + ": no SMS network found, alarm not sent");
      return;
    }

    Map<String, String> v = net.standardValues();
    BFacets data = rec.getAlarmData();
    String source = facet(data, BAlarmRecord.SOURCE_NAME);
    if (source.isEmpty()) source = rec.getSource().toString();
    v.put("source", source);
    v.put("msg", formatMessage(facet(data, BAlarmRecord.MSG_TEXT)));
    v.put("alarmClass", rec.getAlarmClass());
    v.put("priority", Integer.toString(rec.getPriority()));
    v.put("value", facet(data, BAlarmRecord.PRESENT_VALUE));
    v.put("user", rec.getUser());
    v.put("time", net.formatTime(rec.getTimestamp().getMillis()));

    boolean acked = rec.isAcknowledged();
    String state = acked ? "ACKNOWLEDGED" : rec.getSourceState().getDisplayTag(null).toUpperCase();
    v.put("state", state);

    String ackCode = "";
    if (!acked && rec.getAckRequired() && getIncludeAckCode() && !rec.isNormal())
      ackCode = net.registerAlarm(rec.getUuid());
    v.put("ackCode", ackCode);
    v.put("ackHint", ackCode.isEmpty() ? "" : " Reply ACK " + ackCode);

    String text = Templates.render(acked ? getAckText() : getAlarmText(), v);
    int priority = Math.max(1, Math.min(255, rec.getPriority()));
    int n = net.queueToRecipients(getRecipients(), text, "Alarm " + rec.getAlarmClass(), priority);
    if (n > 0)
    {
      setAlarmsForwarded(getAlarmsForwarded() + 1);
      setLastAlarm(source + " " + state);
      setLastAlarmTime(BAbsTime.make());
    }
  }

  private static String facet(BFacets f, String key)
  {
    if (f == null) return "";
    BObject o = f.get(key);
    return o == null ? "" : o.toString();
  }

  /** Alarm text may contain BFormat/lexicon expressions such as %lexicon(...)%. */
  private static String formatMessage(String raw)
  {
    if (raw == null || raw.indexOf('%') < 0) return raw == null ? "" : raw;
    try { return BFormat.make(raw).format(null); }
    catch (Exception e) { return raw; }
  }

  @Override
  public BIcon getIcon() { return ICON; }
  private static final BIcon ICON = BIcon.std("mobile.png");
}
