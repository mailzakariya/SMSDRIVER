/*
 * Copyright (c) Tecum. All Rights Reserved.
 */
package com.tecum.sms;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

import javax.baja.sys.BFacets;
import javax.baja.sys.BIcon;
import javax.baja.sys.BInteger;
import javax.baja.sys.Property;
import javax.baja.sys.Sys;
import javax.baja.sys.Type;

/**
 * GSM/LTE modem reached over TCP: a serial-to-Ethernet converter (Moxa,
 * Lantronix, USR-IOT) in raw TCP server mode, or a cellular router that
 * exposes its modem AT port over TCP.
 */
public class BTcpModemGateway extends BModemGateway
{
  public static final Property address = newProperty(0, "192.168.1.100", null);
  public String getAddress() { return getString(address); }
  public void setAddress(String v) { setString(address, v, null); }

  public static final Property port = newProperty(0, 4001, BFacets.make(BFacets.MIN, BInteger.make(1), BFacets.MAX, BInteger.make(65535)));
  public int getPort() { return getInt(port); }
  public void setPort(int v) { setInt(port, v, null); }

  @Override
  public Type getType() { return TYPE; }
  public static final Type TYPE = Sys.loadType(BTcpModemGateway.class);

  private Socket socket;

  @Override
  protected Streams openStreams() throws Exception
  {
    closeStreams();
    Socket s = new Socket();
    try
    {
      s.connect(new InetSocketAddress(getAddress().trim(), getPort()), 10000);
      s.setKeepAlive(true);
      s.setTcpNoDelay(true);
    }
    catch (IOException e)
    {
      try { s.close(); } catch (IOException ignore) {}
      throw new IOException("Cannot connect to " + getAddress() + ":" + getPort() + " - " + e.getMessage(), e);
    }
    socket = s;
    return new Streams(s.getInputStream(), s.getOutputStream());
  }

  @Override
  protected void closeStreams()
  {
    Socket s = socket;
    socket = null;
    if (s != null)
    {
      try { s.close(); } catch (IOException ignore) {}
    }
  }

  @Override
  public BIcon getIcon() { return ICON; }
  private static final BIcon ICON = BIcon.std("deviceNetwork.png");
}
