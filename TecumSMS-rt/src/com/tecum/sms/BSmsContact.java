/*
 * Copyright (c) Tecum. All Rights Reserved.
 */
package com.tecum.sms;

import javax.baja.naming.SlotPath;
import javax.baja.sys.Action;
import javax.baja.sys.BAbsTime;
import javax.baja.sys.BComponent;
import javax.baja.sys.BIcon;
import javax.baja.sys.Context;
import javax.baja.sys.Flags;
import javax.baja.sys.Property;
import javax.baja.sys.Sys;
import javax.baja.sys.Type;
import javax.baja.util.BDaysOfWeekBits;
import javax.baja.util.BTimeRange;

import com.tecum.sms.core.PhoneNumbers;

/**
 * A person (or on-call phone) that can receive SMS notifications.
 * Optional "on duty" days and hours limit when messages are delivered.
 */
public class BSmsContact extends BComponent
{
  public static final Property enabled = newProperty(Flags.SUMMARY, true, null);
  public boolean getEnabled() { return getBoolean(enabled); }
  public void setEnabled(boolean v) { setBoolean(enabled, v, null); }

  /** International format recommended, e.g. +971501234567. */
  public static final Property phoneNumber = newProperty(Flags.SUMMARY, "+", null);
  public String getPhoneNumber() { return getString(phoneNumber); }
  public void setPhoneNumber(String v) { setString(phoneNumber, v, null); }

  public static final Property description = newProperty(Flags.SUMMARY, "", null);
  public String getDescription() { return getString(description); }
  public void setDescription(String v) { setString(description, v, null); }

  /** Days on which this contact receives messages. */
  public static final Property onDutyDays = newProperty(0, BDaysOfWeekBits.DEFAULT, null);
  public BDaysOfWeekBits getOnDutyDays() { return (BDaysOfWeekBits) get(onDutyDays); }
  public void setOnDutyDays(BDaysOfWeekBits v) { set(onDutyDays, v, null); }

  /** Time of day during which this contact receives messages. */
  public static final Property onDutyTime = newProperty(0, new BTimeRange(), null);
  public BTimeRange getOnDutyTime() { return (BTimeRange) get(onDutyTime); }
  public void setOnDutyTime(BTimeRange v) { set(onDutyTime, v, null); }

  /**
   * Allow this phone to send commands back (ACK &lt;code&gt;, STATUS, HELP)
   * through a modem gateway.
   */
  public static final Property allowSmsCommands = newProperty(0, false, null);
  public boolean getAllowSmsCommands() { return getBoolean(allowSmsCommands); }
  public void setAllowSmsCommands(boolean v) { setBoolean(allowSmsCommands, v, null); }

  public static final Property messagesSent = newProperty(Flags.READONLY | Flags.SUMMARY, 0, null);
  public int getMessagesSent() { return getInt(messagesSent); }
  public void setMessagesSent(int v) { setInt(messagesSent, v, null); }

  public static final Property lastMessageTime = newProperty(Flags.READONLY, BAbsTime.NULL, null);
  public BAbsTime getLastMessageTime() { return (BAbsTime) get(lastMessageTime); }
  public void setLastMessageTime(BAbsTime v) { set(lastMessageTime, v, null); }

  public static final Action sendTestSms = newAction(0, null);
  public void sendTestSms() { invoke(sendTestSms, null, null); }

  @Override
  public Type getType() { return TYPE; }
  public static final Type TYPE = Sys.loadType(BSmsContact.class);

  // ------------------------------------------------------------------ logic

  public String getContactName() { return SlotPath.unescape(getName()); }

  public boolean hasValidNumber() { return PhoneNumbers.isValid(getPhoneNumber()); }

  /** Enabled, has a number, and is on duty right now. */
  public boolean isReachableNow()
  {
    if (!getEnabled() || !hasValidNumber()) return false;
    BAbsTime now = BAbsTime.make();
    try
    {
      if (!getOnDutyDays().includes(now)) return false;
      if (!getOnDutyTime().includes(now)) return false;
    }
    catch (Exception e)
    {
      return true;    // never lose a message because of a bad schedule value
    }
    return true;
  }

  public void doSendTestSms()
  {
    BSmsNetwork net = BSmsNetwork.findFor(this);
    if (net == null) throw new IllegalStateException("Contact is not inside an SMS network");
    if (!hasValidNumber()) throw new IllegalStateException("Invalid phone number: " + getPhoneNumber());
    net.queueToPhone(getPhoneNumber(), net.renderStandard("Test message from Niagara {station} to " + getContactName() + " at {time}"),
                     "Test", getContactName(), 1);
  }

  @Override
  public void changed(Property p, Context cx)
  {
    super.changed(p, cx);
    if (p == phoneNumber && isRunning())
    {
      String n = PhoneNumbers.normalize(getPhoneNumber());
      if (!n.isEmpty() && !n.equals(getPhoneNumber())) setPhoneNumber(n);
    }
  }

  @Override
  public boolean isParentLegal(BComponent parent)
  {
    return parent instanceof BSmsContactFolder
        || parent instanceof javax.baja.util.BUnrestrictedFolder;
  }

  @Override
  public String toString(Context cx) { return getContactName() + " <" + getPhoneNumber() + ">"; }

  @Override
  public BIcon getIcon() { return ICON; }
  private static final BIcon ICON = BIcon.std("user.png");
}
