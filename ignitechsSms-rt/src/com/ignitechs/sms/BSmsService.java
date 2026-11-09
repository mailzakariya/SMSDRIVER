/*
 * Copyright (c) Ignitechs. All Rights Reserved.
 */
package com.ignitechs.sms;

import javax.baja.sys.Sys;
import javax.baja.sys.Type;

/**
 * SMS Notification Service: placed directly under Station -> Config -> Services.
 *
 * Contains:
 *  - Internet & network SMS gateways (TextBeeCloud, SmsGateCloud, NtfyInternetAlert, SMPP, HTTP)
 *  - Contacts: address book with phone numbers & on-duty schedules
 *  - Groups: named teams of contacts
 *  - Rules: point monitoring rules & wire-sheet senders
 */
public class BSmsService extends BSmsNetwork
{
  @Override
  public Type getType() { return TYPE; }
  public static final Type TYPE = Sys.loadType(BSmsService.class);

  private static final Type[] SERVICE_TYPES = new Type[] { TYPE, BSmsNetwork.TYPE };

  @Override
  public Type[] getServiceTypes() { return SERVICE_TYPES; }
}
