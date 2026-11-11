/*
 * Copyright (c) Tecum. All Rights Reserved.
 */
package com.tecum.sms;

import javax.baja.naming.SlotPath;
import javax.baja.status.BStatusBoolean;
import javax.baja.sys.Action;
import javax.baja.sys.BAbsTime;
import javax.baja.sys.BFacets;
import javax.baja.sys.BIcon;
import javax.baja.sys.BInteger;
import javax.baja.sys.BString;
import javax.baja.sys.Context;
import javax.baja.sys.Flags;
import javax.baja.sys.Property;
import javax.baja.sys.Sys;
import javax.baja.sys.Type;

/**
 * Wire-sheet block that sends a fixed message when its "trigger" input goes
 * true (or when the Send action is invoked). Link any boolean logic output to
 * "trigger". Can live in the SMS network Rules folder or anywhere in the station.
 */
public class BSmsSender extends javax.baja.sys.BComponent
{
  public static final Property enabled = newProperty(Flags.SUMMARY, true, null);
  public boolean getEnabled() { return getBoolean(enabled); }
  public void setEnabled(boolean v) { setBoolean(enabled, v, null); }

  /** Rising edge (false to true) sends the message. */
  public static final Property trigger = newProperty(Flags.SUMMARY, new BStatusBoolean(false), null);
  public BStatusBoolean getTrigger() { return (BStatusBoolean) get(trigger); }
  public void setTrigger(BStatusBoolean v) { set(trigger, v, null); }

  /** Placeholders: {station} {time} {sender} */
  public static final Property message = newProperty(0, "{station}: {sender} triggered at {time}",
    BFacets.make(BFacets.FIELD_WIDTH, BInteger.make(70)));
  public String getMessage() { return getString(message); }
  public void setMessage(String v) { setString(message, v, null); }

  public static final Property messagePriority = newProperty(0, 5, BFacets.make(BFacets.MIN, BInteger.make(1), BFacets.MAX, BInteger.make(255)));
  public int getMessagePriority() { return getInt(messagePriority); }
  public void setMessagePriority(int v) { setInt(messagePriority, v, null); }

  public static final Property recipients = newProperty(0, new BSmsRecipientList(), null);
  public BSmsRecipientList getRecipients() { return (BSmsRecipientList) get(recipients); }
  public void setRecipients(BSmsRecipientList v) { set(recipients, v, null); }

  public static final Property lastSent = newProperty(Flags.READONLY, BAbsTime.NULL, null);
  public BAbsTime getLastSent() { return (BAbsTime) get(lastSent); }
  public void setLastSent(BAbsTime v) { set(lastSent, v, null); }

  public static final Property sendCount = newProperty(Flags.READONLY, 0, null);
  public int getSendCount() { return getInt(sendCount); }
  public void setSendCount(int v) { setInt(sendCount, v, null); }

  /** Send the configured message now. */
  public static final Action send = newAction(Flags.SUMMARY, null);
  public void send() { invoke(send, null, null); }

  /** Send a custom text now (can be linked from a string output). */
  public static final Action sendText = newAction(0, BString.make(""), null);
  public void sendText(BString text) { invoke(sendText, text, null); }

  @Override
  public Type getType() { return TYPE; }
  public static final Type TYPE = Sys.loadType(BSmsSender.class);

  private boolean lastTrigger;
  private BSmsNetwork registeredWith;

  @Override
  public void started() throws Exception
  {
    super.started();
    lastTrigger = getTrigger().getValue();
    BSmsNetwork net = network();
    if (net != null)
    {
      if (BSmsNetwork.findFor(this) == null) { net.registerExternal(getRecipients()); registeredWith = net; }
      else getRecipients().sync(net);
    }
  }

  @Override
  public void stopped() throws Exception
  {
    if (registeredWith != null) { registeredWith.unregisterExternal(getRecipients()); registeredWith = null; }
    super.stopped();
  }

  @Override
  public void changed(Property p, Context cx)
  {
    super.changed(p, cx);
    if (p != trigger || !isRunning()) return;
    boolean v = getTrigger().getValue() && getTrigger().getStatus().isValid();
    if (v && !lastTrigger) doSend();
    lastTrigger = v;
  }

  public void doSend()
  {
    sendNow(getMessage());
  }

  public void doSendText(BString text)
  {
    sendNow(text == null || text.getString().trim().isEmpty() ? getMessage() : text.getString());
  }

  private void sendNow(String template)
  {
    if (!getEnabled()) return;
    BSmsNetwork net = network();
    if (net == null) throw new IllegalStateException("No SMS network found in the station");
    java.util.Map<String, String> v = net.standardValues();
    v.put("sender", SlotPath.unescape(getName()));
    String text = com.tecum.sms.core.Templates.render(template, v);
    int n = net.queueToRecipients(getRecipients(), text, SlotPath.unescape(getName()), getMessagePriority());
    if (n > 0)
    {
      setSendCount(getSendCount() + 1);
      setLastSent(BAbsTime.make());
    }
  }

  private BSmsNetwork network()
  {
    BSmsNetwork n = BSmsNetwork.findFor(this);
    return n != null ? n : BSmsNetwork.findInStation();
  }

  @Override
  public BIcon getIcon() { return ICON; }
  private static final BIcon ICON = BIcon.std("phone.png");
}
