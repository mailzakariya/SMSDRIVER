/*
 * Copyright (c) Ignitechs. All Rights Reserved.
 */
package com.ignitechs.sms;

import java.util.Map;
import java.util.logging.Level;

import javax.baja.control.BControlPoint;
import javax.baja.naming.BOrd;
import javax.baja.naming.OrdTarget;
import javax.baja.naming.SlotPath;
import javax.baja.status.BIStatusValue;
import javax.baja.status.BStatus;
import javax.baja.status.BStatusBoolean;
import javax.baja.status.BStatusEnum;
import javax.baja.status.BStatusNumeric;
import javax.baja.status.BStatusString;
import javax.baja.status.BStatusValue;
import javax.baja.sys.Action;
import javax.baja.sys.BAbsTime;
import javax.baja.sys.BBoolean;
import javax.baja.sys.BComponent;
import javax.baja.sys.BComponentEvent;
import javax.baja.sys.BDynamicEnum;
import javax.baja.sys.BEnum;
import javax.baja.sys.BFacets;
import javax.baja.sys.BIcon;
import javax.baja.sys.BInteger;
import javax.baja.sys.BNumber;
import javax.baja.sys.BObject;
import javax.baja.sys.BRelTime;
import javax.baja.sys.BString;
import javax.baja.sys.Clock;
import javax.baja.sys.Context;
import javax.baja.sys.Flags;
import javax.baja.sys.Property;
import javax.baja.sys.Subscriber;
import javax.baja.sys.Sys;
import javax.baja.sys.Type;

import com.ignitechs.sms.core.PointSample;
import com.ignitechs.sms.core.RuleEvaluator;
import com.ignitechs.sms.core.Templates;

/**
 * Watches one point (or any status/value slot) and sends an SMS to the
 * selected contacts and groups when the condition is met.
 *
 * Template placeholders: {station} {time} {point} {path} {value} {status}
 * {state} {rule} {condition} {limit} {low} {high} {compare} {description}
 */
public class BSmsPointRule extends BComponent
{
  // ------------------------------------------------------------------ configuration

  public static final Property enabled = newProperty(Flags.SUMMARY, true, null);
  public boolean getEnabled() { return getBoolean(enabled); }
  public void setEnabled(boolean v) { setBoolean(enabled, v, null); }

  /** Point to watch, e.g. station:|slot:/Drivers/BacnetNetwork/AHU1/points/SAT */
  public static final Property sourceOrd = newProperty(Flags.SUMMARY, BOrd.NULL, null);
  public BOrd getSourceOrd() { return (BOrd) get(sourceOrd); }
  public void setSourceOrd(BOrd v) { set(sourceOrd, v, null); }

  /** Free text, available as {description} in messages. */
  public static final Property description = newProperty(0, "", null);
  public String getDescription() { return getString(description); }
  public void setDescription(String v) { setString(description, v, null); }

  public static final Property condition = newProperty(Flags.SUMMARY, BSmsCondition.DEFAULT, null);
  public BSmsCondition getCondition() { return (BSmsCondition) get(condition); }
  public void setCondition(BSmsCondition v) { set(condition, v, null); }

  /**
   * For "value equals / not equals": one or more states or values separated
   * by '|' or ',' - e.g. "Alarm", "Fault|Trip", "true", "On", "3", "Unoccupied".
   */
  public static final Property compareValue = newProperty(0, "", null);
  public String getCompareValue() { return getString(compareValue); }
  public void setCompareValue(String v) { setString(compareValue, v, null); }

  /** For greater / less than conditions. */
  public static final Property limit = newProperty(0, 0d, null);
  public double getLimit() { return getDouble(limit); }
  public void setLimit(double v) { setDouble(limit, v, null); }

  /** For inside / outside range conditions. */
  public static final Property lowLimit = newProperty(0, 0d, null);
  public double getLowLimit() { return getDouble(lowLimit); }
  public void setLowLimit(double v) { setDouble(lowLimit, v, null); }

  public static final Property highLimit = newProperty(0, 100d, null);
  public double getHighLimit() { return getDouble(highLimit); }
  public void setHighLimit(double v) { setDouble(highLimit, v, null); }

  /** Hysteresis for numeric limits: the value must move back this far before returning to normal. */
  public static final Property deadband = newProperty(0, 0d, BFacets.make(BFacets.MIN, javax.baja.sys.BDouble.make(0)));
  public double getDeadband() { return getDouble(deadband); }
  public void setDeadband(double v) { setDouble(deadband, v, null); }

  /** The condition must hold this long before a message is sent (filters short spikes). */
  public static final Property activeDelay = newProperty(0, BRelTime.makeSeconds(0), null);
  public BRelTime getActiveDelay() { return (BRelTime) get(activeDelay); }
  public void setActiveDelay(BRelTime v) { set(activeDelay, v, null); }

  /**
   * Re-send while still active at this interval (0 = send once).
   * For "any change" this is the minimum time between messages.
   */
  public static final Property repeatInterval = newProperty(0, BRelTime.makeSeconds(0), null);
  public BRelTime getRepeatInterval() { return (BRelTime) get(repeatInterval); }
  public void setRepeatInterval(BRelTime v) { set(repeatInterval, v, null); }

  /** Maximum number of repeats (0 = unlimited while active). */
  public static final Property maxRepeats = newProperty(0, 3, BFacets.make(BFacets.MIN, BInteger.make(0)));
  public int getMaxRepeats() { return getInt(maxRepeats); }
  public void setMaxRepeats(int v) { setInt(maxRepeats, v, null); }

  /** Send the normal text when the condition clears. */
  public static final Property notifyOnNormal = newProperty(0, true, null);
  public boolean getNotifyOnNormal() { return getBoolean(notifyOnNormal); }
  public void setNotifyOnNormal(boolean v) { setBoolean(notifyOnNormal, v, null); }

  /** Queue priority, 1 = most urgent. */
  public static final Property messagePriority = newProperty(0, 5, BFacets.make(BFacets.MIN, BInteger.make(1), BFacets.MAX, BInteger.make(255)));
  public int getMessagePriority() { return getInt(messagePriority); }
  public void setMessagePriority(int v) { setInt(messagePriority, v, null); }

  public static final Property alertText = newProperty(0, "{station} {state}: {point} = {value} ({condition} {compare}{limit}) {time}",
    BFacets.make(BFacets.FIELD_WIDTH, BInteger.make(70)));
  public String getAlertText() { return getString(alertText); }
  public void setAlertText(String v) { setString(alertText, v, null); }

  public static final Property normalText = newProperty(0, "{station} NORMAL: {point} = {value} {time}",
    BFacets.make(BFacets.FIELD_WIDTH, BInteger.make(70)));
  public String getNormalText() { return getString(normalText); }
  public void setNormalText(String v) { setString(normalText, v, null); }

  /** Tick the contacts and groups that receive this rule's messages. */
  public static final Property recipients = newProperty(0, new BSmsRecipientList(), null);
  public BSmsRecipientList getRecipients() { return (BSmsRecipientList) get(recipients); }
  public void setRecipients(BSmsRecipientList v) { set(recipients, v, null); }

  // ------------------------------------------------------------------ runtime status

  public static final Property active = newProperty(Flags.READONLY | Flags.TRANSIENT | Flags.SUMMARY, false, null);
  public boolean getActive() { return getBoolean(active); }
  public void setActive(boolean v) { setBoolean(active, v, null); }

  public static final Property ruleState = newProperty(Flags.READONLY | Flags.TRANSIENT | Flags.SUMMARY, "", null);
  public String getRuleState() { return getString(ruleState); }
  public void setRuleState(String v) { setString(ruleState, v, null); }

  public static final Property currentValue = newProperty(Flags.READONLY | Flags.TRANSIENT | Flags.SUMMARY, "", null);
  public String getCurrentValue() { return getString(currentValue); }
  public void setCurrentValue(String v) { setString(currentValue, v, null); }

  public static final Property sourceStatus = newProperty(Flags.READONLY | Flags.TRANSIENT, "", null);
  public String getSourceStatus() { return getString(sourceStatus); }
  public void setSourceStatus(String v) { setString(sourceStatus, v, null); }

  public static final Property lastNotification = newProperty(Flags.READONLY, BAbsTime.NULL, null);
  public BAbsTime getLastNotification() { return (BAbsTime) get(lastNotification); }
  public void setLastNotification(BAbsTime v) { set(lastNotification, v, null); }

  public static final Property notificationCount = newProperty(Flags.READONLY, 0, null);
  public int getNotificationCount() { return getInt(notificationCount); }
  public void setNotificationCount(int v) { setInt(notificationCount, v, null); }

  // ------------------------------------------------------------------ actions

  /** Send the alert text now with the current value, regardless of the condition. */
  public static final Action sendTestSms = newAction(0, null);
  public void sendTestSms() { invoke(sendTestSms, null, null); }

  /** Return the rule to normal without sending, and re-resolve the source. */
  public static final Action resetRule = newAction(0, null);
  public void resetRule() { invoke(resetRule, null, null); }

  @Override
  public Type getType() { return TYPE; }
  public static final Type TYPE = Sys.loadType(BSmsPointRule.class);

  // ------------------------------------------------------------------ state

  private final RuleEvaluator evaluator = new RuleEvaluator();
  private volatile BComponent sourceComp;
  private volatile Property sourceProp;
  private volatile boolean needResolve = true;
  private long lastResolveAttempt = Long.MIN_VALUE / 2;
  private PointSample lastSample;

  private final Subscriber subscriber = new Subscriber()
  {
    @Override
    public void event(BComponentEvent e)
    {
      if (e.getId() == BComponentEvent.PROPERTY_CHANGED) evaluate(Clock.millis());
    }
  };

  // ------------------------------------------------------------------ lifecycle

  @Override
  public void started() throws Exception
  {
    super.started();
    needResolve = true;
    BSmsNetwork net = BSmsNetwork.findFor(this);
    if (net != null) getRecipients().sync(net);
  }

  @Override
  public void stopped() throws Exception
  {
    release();
    super.stopped();
  }

  @Override
  public void changed(Property p, Context cx)
  {
    super.changed(p, cx);
    if (!isRunning()) return;
    if (p == sourceOrd) { needResolve = true; lastResolveAttempt = Long.MIN_VALUE / 2; }
    else if (p == condition || p == enabled) { evaluator.reset(); setActive(false); }
  }

  public void doResetRule()
  {
    evaluator.reset();
    setActive(false);
    needResolve = true;
    lastResolveAttempt = Long.MIN_VALUE / 2;
  }

  public void doSendTestSms()
  {
    BSmsNetwork net = BSmsNetwork.findFor(this);
    if (net == null) throw new IllegalStateException("Rule is not inside an SMS network");
    resolveIfNeeded(Clock.millis());
    PointSample s = sample();
    int n = net.queueToRecipients(getRecipients(), render(getAlertText(), s, "TEST"), ruleName(), 1);
    net.log("Rule " + ruleName() + ": test message queued for " + n + " recipient(s)");
  }

  // ------------------------------------------------------------------ evaluation

  /** Called every second by the network. */
  void scan(long now)
  {
    if (!getEnabled())
    {
      if (!"Disabled".equals(getRuleState())) { release(); setRuleState("Disabled"); setActive(false); }
      return;
    }
    resolveIfNeeded(now);
    evaluate(now);
  }

  private synchronized void evaluate(long now)
  {
    if (!isRunning() || !getEnabled()) return;
    BSmsNetwork net = BSmsNetwork.findFor(this);
    if (net == null) return;

    PointSample s = sample();
    lastSample = s;
    if (s == null) return;

    updateText(currentValue, s.display);
    updateText(sourceStatus, s.statusText);

    RuleEvaluator.Decision d = evaluator.update(settings(), s, now);
    boolean isActive = evaluator.isActive();
    if (getActive() != isActive) setActive(isActive);
    updateText(ruleState, describeState());

    switch (d)
    {
      case ALERT: notify(net, render(getAlertText(), s, "ALERT")); break;
      case REPEAT: notify(net, render(getAlertText(), s, "REMINDER")); break;
      case CHANGE: notify(net, render(getAlertText(), s, "CHANGE")); break;
      case NORMAL: if (getNotifyOnNormal()) notify(net, render(getNormalText(), s, "NORMAL")); break;
      default: break;
    }
  }

  private void notify(BSmsNetwork net, String text)
  {
    int n = net.queueToRecipients(getRecipients(), text, ruleName(), getMessagePriority());
    if (n > 0)
    {
      setNotificationCount(getNotificationCount() + 1);
      setLastNotification(BAbsTime.make());
    }
  }

  private String describeState()
  {
    switch (evaluator.getState())
    {
      case ACTIVE: return "Active";
      case PENDING: return "Pending (delay)";
      default: return "Normal";
    }
  }

  private RuleEvaluator.Settings settings()
  {
    RuleEvaluator.Settings s = new RuleEvaluator.Settings();
    s.condition = getCondition().getOrdinal();
    s.compareValue = getCompareValue();
    s.limit = getLimit();
    s.lowLimit = Math.min(getLowLimit(), getHighLimit());
    s.highLimit = Math.max(getLowLimit(), getHighLimit());
    s.deadband = getDeadband();
    s.activeDelayMs = getActiveDelay().getMillis();
    s.repeatIntervalMs = getRepeatInterval().getMillis();
    s.maxRepeats = getMaxRepeats();
    return s;
  }

  private void updateText(Property p, String v)
  {
    String nv = v == null ? "" : v;
    if (!nv.equals(getString(p))) setString(p, nv, null);
  }

  // ------------------------------------------------------------------ source

  private void resolveIfNeeded(long now)
  {
    BComponent c = sourceComp;
    if (c != null && !c.isMounted()) needResolve = true;
    if (!needResolve) return;
    if (now - lastResolveAttempt < 30000) return;
    lastResolveAttempt = now;
    release();

    BOrd ord = getSourceOrd();
    if (ord == null || ord.isNull())
    {
      updateText(ruleState, "No source selected");
      return;
    }
    try
    {
      OrdTarget t = ord.resolve(this);
      BObject obj = t.get();
      BComponent comp;
      Property prop = null;
      if (obj instanceof BComponent) comp = (BComponent) obj;
      else
      {
        comp = t.getComponent();
        if (comp == null) throw new IllegalArgumentException("Source is not in a component");
        for (Property p : comp.getPropertiesArray())
          if (comp.get(p) == obj) { prop = p; break; }
        if (prop == null) throw new IllegalArgumentException("Source slot not found");
      }
      sourceComp = comp;
      sourceProp = prop;
      needResolve = false;
      // a permanent subscription keeps proxy points polled, like an alarm extension
      subscriber.subscribe(comp);
      updateText(ruleState, "Resolved");
    }
    catch (Exception e)
    {
      BSmsNetwork.LOG.log(Level.FINE, "Cannot resolve " + ord, e);
      updateText(ruleState, "Source not found: " + e.getMessage());
    }
  }

  private void release()
  {
    try { subscriber.unsubscribeAll(); } catch (Exception ignore) {}
    sourceComp = null;
    sourceProp = null;
    needResolve = true;
  }

  /** Current value of the source, or null when not resolved / unsupported. */
  PointSample sample()
  {
    BComponent c = sourceComp;
    if (c == null) return null;
    Property p = sourceProp;
    BObject v;
    BFacets f = BFacets.NULL;
    if (p == null)
    {
      if (c instanceof BControlPoint)
      {
        v = ((BControlPoint) c).getOutStatusValue();
        f = ((BControlPoint) c).getFacets();
      }
      else if (c instanceof BIStatusValue) v = ((BIStatusValue) c).getStatusValue();
      else
      {
        updateText(ruleState, "Unsupported source type " + c.getType());
        return null;
      }
    }
    else
    {
      v = c.get(p);
      f = c instanceof BControlPoint ? ((BControlPoint) c).getFacets() : c.getSlotFacets(p);
    }
    return toSample(v, f == null ? BFacets.NULL : f);
  }

  static PointSample toSample(BObject v, BFacets f)
  {
    PointSample s;
    if (v instanceof BStatusValue)
    {
      BStatusValue sv = (BStatusValue) v;
      if (sv instanceof BStatusNumeric) s = PointSample.numeric(((BStatusNumeric) sv).getValue());
      else if (sv instanceof BStatusBoolean) s = PointSample.bool(((BStatusBoolean) sv).getValue());
      else if (sv instanceof BStatusEnum)
      {
        BDynamicEnum e = ((BStatusEnum) sv).getValue();
        s = PointSample.enumeration(e.getOrdinal(), e.getTag(), "");
      }
      else if (sv instanceof BStatusString) s = PointSample.string(((BStatusString) sv).getValue());
      else s = PointSample.string(sv.valueToString(f));
      try { s.display = sv.valueToString(f); } catch (Exception ignore) {}
      BStatus st = sv.getStatus();
      s.statusText = st.toString(null);
      s.alarm = st.isAlarm();
      s.fault = st.isFault();
      s.down = st.isDown();
      s.overridden = st.isOverridden();
      s.stale = st.isStale();
      s.isNull = st.isNull();
      s.disabled = st.isDisabled();
      return s;
    }
    if (v instanceof BBoolean)
    {
      s = PointSample.bool(((BBoolean) v).getBoolean());
      s.display = ((BBoolean) v).toString(f);
    }
    else if (v instanceof BNumber)
    {
      s = PointSample.numeric(((BNumber) v).getDouble());
      s.display = ((BNumber) v).toString(f);
    }
    else if (v instanceof BEnum)
    {
      BEnum e = (BEnum) v;
      s = PointSample.enumeration(e.getOrdinal(), e.getTag(), e.toString(f));
    }
    else if (v instanceof BString) s = PointSample.string(((BString) v).getString());
    else s = PointSample.string(v == null ? "" : v.toString());
    return s;
  }

  // ------------------------------------------------------------------ messages

  String ruleName() { return SlotPath.unescape(getName()); }

  private String render(String template, PointSample s, String state)
  {
    BSmsNetwork net = BSmsNetwork.findFor(this);
    Map<String, String> v = net != null ? net.standardValues() : new java.util.HashMap<String, String>();
    BComponent c = sourceComp;
    v.put("point", c == null ? "?" : c.getDisplayName(null));
    v.put("path", c == null ? getSourceOrd().toString() : c.getSlotPath().toString());
    v.put("value", s == null ? "?" : s.display);
    v.put("status", s == null ? "" : s.statusText);
    v.put("state", state);
    v.put("rule", ruleName());
    v.put("description", getDescription());
    v.put("condition", conditionText());
    v.put("compare", needsCompare() ? getCompareValue() : "");
    v.put("limit", limitText());
    v.put("low", fmt(getLowLimit()));
    v.put("high", fmt(getHighLimit()));
    return Templates.render(template, v).replaceAll(" \\( ?\\)", "").replaceAll("\\s{2,}", " ").trim();
  }

  private boolean needsCompare()
  {
    int c = getCondition().getOrdinal();
    return c == BSmsCondition.VALUE_EQUALS || c == BSmsCondition.VALUE_NOT_EQUALS;
  }

  private String conditionText()
  {
    switch (getCondition().getOrdinal())
    {
      case BSmsCondition.VALUE_EQUALS: return "=";
      case BSmsCondition.VALUE_NOT_EQUALS: return "not";
      case BSmsCondition.GREATER_THAN: return ">";
      case BSmsCondition.LESS_THAN: return "<";
      case BSmsCondition.GREATER_OR_EQUAL: return ">=";
      case BSmsCondition.LESS_OR_EQUAL: return "<=";
      case BSmsCondition.OUTSIDE_RANGE: return "outside";
      case BSmsCondition.INSIDE_RANGE: return "inside";
      case BSmsCondition.IS_TRUE: return "active";
      case BSmsCondition.IS_FALSE: return "inactive";
      case BSmsCondition.ANY_CHANGE: return "changed";
      case BSmsCondition.STATUS_ALARM: return "in alarm";
      case BSmsCondition.STATUS_FAULT: return "fault";
      case BSmsCondition.STATUS_DOWN: return "offline";
      case BSmsCondition.STATUS_OVERRIDDEN: return "overridden";
      case BSmsCondition.STATUS_NOT_OK: return "status not ok";
      default: return getCondition().getTag();
    }
  }

  private String limitText()
  {
    switch (getCondition().getOrdinal())
    {
      case BSmsCondition.GREATER_THAN: case BSmsCondition.LESS_THAN:
      case BSmsCondition.GREATER_OR_EQUAL: case BSmsCondition.LESS_OR_EQUAL:
        return fmt(getLimit());
      case BSmsCondition.OUTSIDE_RANGE: case BSmsCondition.INSIDE_RANGE:
        return fmt(getLowLimit()) + ".." + fmt(getHighLimit());
      default:
        return "";
    }
  }

  static String fmt(double d)
  {
    if (d == Math.rint(d) && Math.abs(d) < 1e12) return Long.toString((long) d);
    return String.valueOf(Math.round(d * 100.0) / 100.0);
  }

  @Override
  public String toString(Context cx)
  {
    return conditionText() + " " + (needsCompare() ? getCompareValue() : limitText()) + " -> " + getRecipients().summary();
  }

  @Override
  public BIcon getIcon() { return ICON; }
  private static final BIcon ICON = BIcon.std("rule.png");
}
