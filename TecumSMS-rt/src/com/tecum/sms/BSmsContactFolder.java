/*
 * Copyright (c) Tecum. All Rights Reserved.
 */
package com.tecum.sms;

import javax.baja.sys.BComponent;
import javax.baja.sys.BIcon;
import javax.baja.sys.BValue;
import javax.baja.sys.Context;
import javax.baja.sys.Property;
import javax.baja.sys.Sys;
import javax.baja.sys.Type;
import javax.baja.util.BFolder;

/**
 * Address book of the SMS network. Adding, renaming or removing a contact
 * updates every recipient checklist automatically.
 */
public class BSmsContactFolder extends BFolder
{
  @Override
  public Type getType() { return TYPE; }
  public static final Type TYPE = Sys.loadType(BSmsContactFolder.class);

  @Override
  public boolean isChildLegal(BComponent child) { return child instanceof BSmsContact; }

  @Override
  public boolean isParentLegal(BComponent parent)
  {
    return parent instanceof BSmsNetwork
        || parent instanceof javax.baja.util.BUnrestrictedFolder;
  }

  @Override
  public void added(Property p, Context cx)
  {
    super.added(p, cx);
    if (isRunning() && get(p) instanceof BSmsContact) BSmsNetwork.syncLater(this);
  }

  @Override
  public void removed(Property p, BValue old, Context cx)
  {
    super.removed(p, old, cx);
    if (isRunning() && old instanceof BSmsContact) BSmsNetwork.syncLater(this);
  }

  @Override
  public void renamed(Property p, String oldName, Context cx)
  {
    super.renamed(p, oldName, cx);
    if (isRunning() && get(p) instanceof BSmsContact)
    {
      BSmsNetwork net = BSmsNetwork.findFor(this);
      if (net != null) net.recipientRenamed(false, oldName, p.getName());
    }
  }

  @Override
  public BIcon getIcon() { return ICON; }
  private static final BIcon ICON = BIcon.std("users.png");
}
