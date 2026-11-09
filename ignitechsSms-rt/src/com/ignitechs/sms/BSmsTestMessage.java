/*
 * Copyright (c) Ignitechs. All Rights Reserved.
 */
package com.ignitechs.sms;

import javax.baja.sys.BStruct;
import javax.baja.sys.Property;
import javax.baja.sys.Sys;
import javax.baja.sys.Type;

/** Argument for the "Send Test Sms" actions. */
public final class BSmsTestMessage extends BStruct
{
  public static final Property phoneNumber = newProperty(0, "+", null);
  public String getPhoneNumber() { return getString(phoneNumber); }
  public void setPhoneNumber(String v) { setString(phoneNumber, v, null); }

  public static final Property message = newProperty(0, "Test message from Niagara {station} at {time}", null);
  public String getMessage() { return getString(message); }
  public void setMessage(String v) { setString(message, v, null); }

  @Override
  public Type getType() { return TYPE; }
  public static final Type TYPE = Sys.loadType(BSmsTestMessage.class);
}
