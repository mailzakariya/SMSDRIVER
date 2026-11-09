/*
 * Copyright (c) Ignitechs. All Rights Reserved.
 */
package com.ignitechs.sms;

import javax.baja.naming.SlotPath;
import javax.baja.sys.BComponent;
import javax.baja.sys.BIcon;
import javax.baja.sys.Context;
import javax.baja.sys.Flags;
import javax.baja.sys.Property;
import javax.baja.sys.Sys;
import javax.baja.sys.Type;

/**
 * A named set of contacts (e.g. "Maintenance", "Night shift"). Select the
 * group in a rule or alarm recipient to message all of its members.
 */
public class BSmsGroup extends BComponent
{
  public static final Property enabled = newProperty(Flags.SUMMARY, true, null);
  public boolean getEnabled() { return getBoolean(enabled); }
  public void setEnabled(boolean v) { setBoolean(enabled, v, null); }

  public static final Property description = newProperty(Flags.SUMMARY, "", null);
  public String getDescription() { return getString(description); }
  public void setDescription(String v) { setString(description, v, null); }

  /** Tick the contacts that belong to this group. */
  public static final Property members = newProperty(0, new BSmsRecipientList(), null);
  public BSmsRecipientList getMembers() { return (BSmsRecipientList) get(members); }
  public void setMembers(BSmsRecipientList v) { set(members, v, null); }

  @Override
  public Type getType() { return TYPE; }
  public static final Type TYPE = Sys.loadType(BSmsGroup.class);

  public String getGroupName() { return SlotPath.unescape(getName()); }

  @Override
  public boolean isParentLegal(BComponent parent) { return parent instanceof BSmsGroupFolder; }

  @Override
  public String toString(Context cx) { return getGroupName() + ": " + getMembers().summary(); }

  @Override
  public BIcon getIcon() { return ICON; }
  private static final BIcon ICON = BIcon.std("users.png");
}
