/*
 * Copyright (c) Tecum. All Rights Reserved.
 */
package com.tecum.sms;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.baja.naming.SlotPath;
import javax.baja.sys.BBoolean;
import javax.baja.sys.BComponent;
import javax.baja.sys.BIcon;
import javax.baja.sys.Context;
import javax.baja.sys.Property;
import javax.baja.sys.Slot;
import javax.baja.sys.Sys;
import javax.baja.sys.Type;

/**
 * A checklist of every contact and group of the SMS network. Each entry is a
 * dynamic boolean property; tick the ones that should receive the message.
 * Entries are added, renamed and removed automatically when contacts and
 * groups change, so the list is always in sync with the address book.
 *
 * Slot names: "c_&lt;escaped contact name&gt;" and "g_&lt;escaped group name&gt;".
 */
public class BSmsRecipientList extends BComponent
{
  static final String CONTACT_PREFIX = "c_";
  static final String GROUP_PREFIX = "g_";

  // ------------------------------------------------------------------ sync

  /**
   * Bring the checklist in line with the network address book. Existing
   * selections are preserved; entries for deleted contacts are removed.
   */
  public void sync(BSmsNetwork net)
  {
    if (net == null) return;
    Set<String> wanted = new LinkedHashSet<>();
    for (BSmsContact c : net.getContactList()) wanted.add(CONTACT_PREFIX + c.getName());
    // a group's member list offers contacts only (no nested groups)
    boolean groups = !(getParent() instanceof BSmsGroup);
    if (groups)
      for (BSmsGroup g : net.getGroupList()) wanted.add(GROUP_PREFIX + g.getName());

    for (Property p : getPropertiesArray())
    {
      if (!p.isDynamic()) continue;
      if (!wanted.contains(p.getName())) remove(p);
    }
    for (String name : wanted)
      if (getProperty(name) == null) add(name, BBoolean.FALSE, 0);
  }

  /** Keep a selection when a contact or group is renamed. */
  public void renameEntry(boolean group, String oldName, String newName)
  {
    String prefix = group ? GROUP_PREFIX : CONTACT_PREFIX;
    Property p = getProperty(prefix + oldName);
    if (p != null && getProperty(prefix + newName) == null) rename(p, prefix + newName);
  }

  // ------------------------------------------------------------------ selection

  /** Names (slot names, still escaped) of the selected contacts. */
  public List<String> getSelectedContacts() { return selected(CONTACT_PREFIX); }

  /** Names (slot names, still escaped) of the selected groups. */
  public List<String> getSelectedGroups() { return selected(GROUP_PREFIX); }

  public boolean isEmpty() { return getSelectedContacts().isEmpty() && getSelectedGroups().isEmpty(); }

  private List<String> selected(String prefix)
  {
    List<String> out = new ArrayList<>();
    for (Property p : getPropertiesArray())
    {
      if (!p.isDynamic() || !p.getName().startsWith(prefix)) continue;
      if (get(p) instanceof BBoolean && ((BBoolean) get(p)).getBoolean())
        out.add(p.getName().substring(prefix.length()));
    }
    return out;
  }

  /** Programmatic selection, e.g. from the palette or tests. */
  public void select(boolean group, String name, boolean on)
  {
    Property p = getProperty((group ? GROUP_PREFIX : CONTACT_PREFIX) + name);
    if (p != null) set(p, BBoolean.make(on));
  }

  /** Short summary such as "Ops Team, John" for display and logs. */
  public String summary()
  {
    StringBuilder sb = new StringBuilder();
    for (String g : getSelectedGroups()) { if (sb.length() > 0) sb.append(", "); sb.append("[").append(SlotPath.unescape(g)).append("]"); }
    for (String c : getSelectedContacts()) { if (sb.length() > 0) sb.append(", "); sb.append(SlotPath.unescape(c)); }
    return sb.length() == 0 ? "(none)" : sb.toString();
  }

  // ------------------------------------------------------------------ display

  @Override
  public String getDisplayName(Slot slot, Context cx)
  {
    if (slot != null && slot.isDynamic())
    {
      String n = slot.getName();
      if (n.startsWith(GROUP_PREFIX)) return "Group: " + SlotPath.unescape(n.substring(GROUP_PREFIX.length()));
      if (n.startsWith(CONTACT_PREFIX)) return SlotPath.unescape(n.substring(CONTACT_PREFIX.length()));
    }
    return super.getDisplayName(slot, cx);
  }

  @Override
  public String toString(Context cx) { return summary(); }

  @Override
  public BIcon getIcon() { return ICON; }
  private static final BIcon ICON = BIcon.std("users.png");

  @Override
  public Type getType() { return TYPE; }
  public static final Type TYPE = Sys.loadType(BSmsRecipientList.class);
}
