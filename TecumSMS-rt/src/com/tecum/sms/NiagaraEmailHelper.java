/*
 * Copyright (c) Tecum. All Rights Reserved.
 */
package com.tecum.sms;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import javax.baja.naming.BOrd;
import javax.baja.sys.BComponent;
import javax.baja.sys.BObject;
import javax.baja.sys.Sys;
import javax.baja.sys.Type;

/**
 * Reflection bridge to Niagara's built-in email module (javax.baja.email.BEmailService,
 * BOutgoingAccount, and BEmail). Using reflection allows TecumSMS-rt to run even on
 * stations where email-rt is not installed, while seamlessly routing Email-to-SMS
 * messages through Niagara's EmailService or OutgoingAccount when configured.
 */
public final class NiagaraEmailHelper
{
  private NiagaraEmailHelper() {}

  public static void sendViaNiagaraEmail(
    BComponent base, BOrd emailServiceOrd, String toAddress, String subject, String body) throws Exception
  {
    Class<?> emailCls;
    Class<?> serviceCls;
    Class<?> outgoingCls;
    try
    {
      emailCls = Class.forName("javax.baja.email.BEmail");
      serviceCls = Class.forName("javax.baja.email.BEmailService");
      outgoingCls = Class.forName("javax.baja.email.BOutgoingAccount");
    }
    catch (ClassNotFoundException e)
    {
      throw new IllegalStateException("Niagara email-rt module is not loaded in this station", e);
    }

    Constructor<?> ctor = emailCls.getConstructor(String.class, String.class, String.class);
    Object emailObj = ctor.newInstance(toAddress, subject == null ? "" : subject, body == null ? "" : body);

    Object target = resolveEmailTarget(base, emailServiceOrd, serviceCls, outgoingCls);
    if (target == null)
    {
      throw new IllegalStateException(
        "No Niagara EmailService or OutgoingAccount found at "
        + (emailServiceOrd == null ? "Services/EmailService" : emailServiceOrd.toString())
        + ". Add EmailService under Station -> Config -> Services or switch to direct SMTP.");
    }

    if (outgoingCls.isInstance(target))
    {
      Method send = outgoingCls.getMethod("send", emailCls);
      send.invoke(target, emailObj);
    }
    else if (serviceCls.isInstance(target))
    {
      Method send = serviceCls.getMethod("send", emailCls);
      send.invoke(target, emailObj);
    }
    else
    {
      throw new IllegalStateException("ORD does not point to a BEmailService or BOutgoingAccount: " + target.getClass().getName());
    }
  }

  public static String checkNiagaraEmailTarget(BComponent base, BOrd emailServiceOrd) throws Exception
  {
    Class<?> serviceCls = Class.forName("javax.baja.email.BEmailService");
    Class<?> outgoingCls = Class.forName("javax.baja.email.BOutgoingAccount");
    Object target = resolveEmailTarget(base, emailServiceOrd, serviceCls, outgoingCls);
    if (target == null)
      throw new IllegalStateException("Niagara EmailService / OutgoingAccount not found in station");
    if (outgoingCls.isInstance(target))
      return "Niagara OutgoingAccount ready (" + ((BComponent) target).getSlotPath() + ")";
    Method getDefault = serviceCls.getMethod("getDefaultOutgoingAccount");
    Object defAcct = getDefault.invoke(target);
    if (defAcct == null)
      return "Niagara EmailService found (" + ((BComponent) target).getSlotPath() + "), add an OutgoingAccount inside EmailService";
    return "Niagara EmailService ready via " + ((BComponent) defAcct).getSlotPath();
  }

  private static Object resolveEmailTarget(
    BComponent base, BOrd emailServiceOrd, Class<?> serviceCls, Class<?> outgoingCls)
  {
    if (emailServiceOrd != null && !emailServiceOrd.isNull())
    {
      try
      {
        BObject obj = emailServiceOrd.resolve(base == null ? Sys.getStation() : base).get();
        if (outgoingCls.isInstance(obj) || serviceCls.isInstance(obj))
          return obj;
      }
      catch (Exception ignored) {}
    }
    try
    {
      Type svcType = (Type) serviceCls.getField("TYPE").get(null);
      BObject svc = Sys.getService(svcType);
      if (svc != null) return svc;
    }
    catch (Exception ignored) {}
    return null;
  }
}
