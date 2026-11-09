/*
 * Copyright (c) Ignitechs. All Rights Reserved.
 */
package com.ignitechs.sms;

import javax.baja.sys.BFrozenEnum;
import javax.baja.sys.Sys;
import javax.baja.sys.Type;

/**
 * Ready-made request templates for common HTTP SMS gateways. Selecting a
 * preset fills in the URL, method, content type, body and success pattern,
 * which can then be edited.
 */
public final class BHttpPreset extends BFrozenEnum
{
  public static final int CUSTOM = 0;
  public static final int ANDROID_SMS_GATEWAY = 1;
  public static final int PLAY_SMS = 2;
  public static final int KANNEL = 3;
  public static final int TELTONIKA_ROUTER = 4;
  public static final int TWILIO = 5;

  public static final BHttpPreset custom = new BHttpPreset(CUSTOM);
  public static final BHttpPreset androidSmsGateway = new BHttpPreset(ANDROID_SMS_GATEWAY);
  public static final BHttpPreset playSms = new BHttpPreset(PLAY_SMS);
  public static final BHttpPreset kannel = new BHttpPreset(KANNEL);
  public static final BHttpPreset teltonikaRouter = new BHttpPreset(TELTONIKA_ROUTER);
  public static final BHttpPreset twilio = new BHttpPreset(TWILIO);

  public static final BHttpPreset DEFAULT = custom;

  public static BHttpPreset make(int ordinal)
  {
    return (BHttpPreset) custom.getRange().get(ordinal, false);
  }

  private BHttpPreset(int ordinal) { super(ordinal); }

  @Override
  public Type getType() { return TYPE; }
  public static final Type TYPE = Sys.loadType(BHttpPreset.class);
}
