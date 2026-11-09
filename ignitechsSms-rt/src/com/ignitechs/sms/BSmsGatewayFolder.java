/*
 * Copyright (c) Ignitechs. All Rights Reserved.
 */
package com.ignitechs.sms;

import javax.baja.driver.BDeviceFolder;
import javax.baja.sys.Sys;
import javax.baja.sys.Type;

/** Device folder for SMS gateways. */
public class BSmsGatewayFolder extends BDeviceFolder
{
  @Override
  public Type getType() { return TYPE; }
  public static final Type TYPE = Sys.loadType(BSmsGatewayFolder.class);
}
