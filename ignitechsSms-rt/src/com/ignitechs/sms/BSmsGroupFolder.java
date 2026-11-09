/*
 * Copyright (c) Ignitechs. All Rights Reserved.
 */
package com.ignitechs.sms;

import javax.baja.sys.BComponent;
import javax.baja.sys.BIcon;
import javax.baja.sys.BValue;
import javax.baja.sys.Context;
import javax.baja.sys.Property;
import javax.baja.sys.Sys;
import javax.baja.sys.Type;
import javax.baja.util.BFolder;

/** Contact groups of the SMS network. */
public class BSmsGroupFolder extends BFolder
{
  @Override
  public Type getType() { return TYPE; }
  public static final Type TYPE = Sys.loadType(BSmsGroupFolder.class);

  @Override
  public boolean isChildLegal(BComponent child) { return child instanceof BSmsGroup; }

  @Override
  public boolean isParentLegal(BComponent parent) { return parent instanceof BSmsNetwork; }

  @Override
  public void added(Property p, Context cx)
  {
    super.added(p, cx);
    if (isRunning() && get(p) instanceof BSmsGroup) BSmsNetwork.syncLater(this);
  }

  @Override
  public void removed(Property p, BValue old, Context cx)
  {
    super.removed(p, old, cx);
    if (isRunning() && old instanceof BSmsGroup) BSmsNetwork.syncLater(this);
  }

  @Override
  public void renamed(Property p, String oldName, Context cx)
  {
    super.renamed(p, oldName, cx);
    if (isRunning() && get(p) instanceof BSmsGroup)
    {
      BSmsNetwork net = BSmsNetwork.findFor(this);
      if (net != null) net.recipientRenamed(true, oldName, p.getName());
    }
  }

  @Override
  public BIcon getIcon() { return ICON; }
  private static final BIcon ICON = BIcon.std("users.png");
}
