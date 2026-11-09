/*
 * Copyright (c) Ignitechs. All Rights Reserved.
 */
package com.ignitechs.sms.core;

/** One message to one phone number, as held in the dispatcher queue. */
public final class OutgoingSms
{
  public final String phone;
  public final String text;
  /** Who requested it, e.g. "Rule HighTemp" or "Alarm Critical". */
  public final String origin;
  /** Recipient display name, e.g. contact name. */
  public final String recipient;
  /** Lower value = sent first. */
  public final int priority;
  public final long created = System.currentTimeMillis();

  volatile long notBefore = created;
  public volatile int attempts;
  public volatile String lastError;
  public volatile String sentVia;

  public OutgoingSms(String phone, String text, String origin, String recipient, int priority)
  {
    this.phone = phone;
    this.text = text;
    this.origin = origin;
    this.recipient = recipient;
    this.priority = priority;
  }

  @Override public String toString() { return recipient + " <" + phone + ">: " + text; }
}
