/*
 * Copyright (c) Tecum. All Rights Reserved.
 */
package com.tecum.sms;

import javax.baja.sys.BFrozenEnum;
import javax.baja.sys.Sys;
import javax.baja.sys.Type;

/**
 * Ready-made request templates for open-source internet SMS platforms and
 * commercial SMS subscription websites. Selecting a preset fills in the URL,
 * method, content type, headers, body, auth mode and success pattern.
 */
public final class BHttpPreset extends BFrozenEnum
{
  public static final int CUSTOM = 0;
  public static final int TEXTBEE_CLOUD = 1;
  public static final int ANDROID_SMS_GATEWAY_CLOUD = 2;
  public static final int ANDROID_SMS_GATEWAY = 3;
  public static final int TRACCAR_SMS = 4;
  public static final int NTFY_SH = 5;
  public static final int JASMIN_HTTP = 6;
  public static final int PLAY_SMS = 7;
  public static final int KANNEL = 8;
  public static final int TELTONIKA_ROUTER = 9;
  public static final int TWILIO = 10;
  public static final int CLICKSEND = 11;
  public static final int VONAGE = 12;
  public static final int TEXTBELT = 13;
  public static final int MESSAGEBIRD = 14;

  public static final BHttpPreset custom = new BHttpPreset(CUSTOM);
  public static final BHttpPreset textBeeCloud = new BHttpPreset(TEXTBEE_CLOUD);
  public static final BHttpPreset androidSmsGatewayCloud = new BHttpPreset(ANDROID_SMS_GATEWAY_CLOUD);
  public static final BHttpPreset androidSmsGateway = new BHttpPreset(ANDROID_SMS_GATEWAY);
  public static final BHttpPreset traccarSms = new BHttpPreset(TRACCAR_SMS);
  public static final BHttpPreset ntfySh = new BHttpPreset(NTFY_SH);
  public static final BHttpPreset jasminHttp = new BHttpPreset(JASMIN_HTTP);
  public static final BHttpPreset playSms = new BHttpPreset(PLAY_SMS);
  public static final BHttpPreset kannel = new BHttpPreset(KANNEL);
  public static final BHttpPreset teltonikaRouter = new BHttpPreset(TELTONIKA_ROUTER);
  public static final BHttpPreset twilio = new BHttpPreset(TWILIO);
  public static final BHttpPreset clickSend = new BHttpPreset(CLICKSEND);
  public static final BHttpPreset vonage = new BHttpPreset(VONAGE);
  public static final BHttpPreset textbelt = new BHttpPreset(TEXTBELT);
  public static final BHttpPreset messageBird = new BHttpPreset(MESSAGEBIRD);

  public static final BHttpPreset DEFAULT = textBeeCloud;

  public static BHttpPreset make(int ordinal)
  {
    return (BHttpPreset) custom.getRange().get(ordinal, false);
  }

  private BHttpPreset(int ordinal) { super(ordinal); }

  @Override
  public Type getType() { return TYPE; }
  public static final Type TYPE = Sys.loadType(BHttpPreset.class);
}
