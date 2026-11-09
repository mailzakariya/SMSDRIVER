/*
 * Copyright (c) Ignitechs. All Rights Reserved.
 */
package com.ignitechs.sms;

import javax.baja.serial.BISerialHelperParent;
import javax.baja.serial.BISerialPort;
import javax.baja.serial.BSerialBaudRate;
import javax.baja.serial.BSerialHelper;
import javax.baja.sys.BIcon;
import javax.baja.sys.Property;
import javax.baja.sys.Sys;
import javax.baja.sys.Type;

/**
 * GSM/LTE modem connected to a serial or USB-serial (COM) port of the
 * Supervisor PC or JACE. Typical devices: Teltonika TRM, Siemens/Cinterion,
 * Quectel, SIMCom, Huawei USB sticks in modem mode.
 */
public class BSerialModemGateway extends BModemGateway implements BISerialHelperParent
{
  public static final Property serialPortConfig = newProperty(0, makeHelper(), null);
  public BSerialHelper getSerialPortConfig() { return (BSerialHelper) get(serialPortConfig); }
  public void setSerialPortConfig(BSerialHelper v) { set(serialPortConfig, v, null); }

  private static BSerialHelper makeHelper()
  {
    BSerialHelper h = new BSerialHelper();
    h.setBaudRate(BSerialBaudRate.baud115200);
    return h;
  }

  @Override
  public Type getType() { return TYPE; }
  public static final Type TYPE = Sys.loadType(BSerialModemGateway.class);

  private BISerialPort port;

  @Override
  protected Streams openStreams() throws Exception
  {
    closeStreams();
    BSerialHelper h = getSerialPortConfig();
    if (h.getPortName() == null || h.getPortName().isEmpty() || BSerialHelper.noPort.equals(h.getPortName()))
      throw new IllegalStateException("No serial port selected");
    port = h.open(getNetwork().getName() + "." + getName());
    return new Streams(port.getInputStream(), port.getOutputStream());
  }

  @Override
  protected void closeStreams()
  {
    BISerialPort p = port;
    port = null;
    if (p != null)
    {
      try { p.close(); } catch (Exception ignore) {}
    }
  }

  // BISerialHelperParent
  @Override
  public void reopenPort()
  {
    closeConnection();
  }

  @Override
  public BIcon getIcon() { return ICON; }
  private static final BIcon ICON = BIcon.std("serialPort.png");
}
