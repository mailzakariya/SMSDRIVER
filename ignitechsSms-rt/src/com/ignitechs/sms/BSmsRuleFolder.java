/*
 * Copyright (c) Ignitechs. All Rights Reserved.
 */
package com.ignitechs.sms;

import javax.baja.sys.BComponent;
import javax.baja.sys.BIcon;
import javax.baja.sys.Sys;
import javax.baja.sys.Type;
import javax.baja.util.BFolder;

/**
 * Holds point rules and senders. Sub-folders are allowed so rules can be
 * organised per building, floor or system.
 */
public class BSmsRuleFolder extends BFolder
{
  @Override
  public Type getType() { return TYPE; }
  public static final Type TYPE = Sys.loadType(BSmsRuleFolder.class);

  @Override
  public boolean isChildLegal(BComponent child)
  {
    return child instanceof BSmsPointRule || child instanceof BSmsSender || child instanceof BSmsRuleFolder;
  }

  @Override
  public boolean isParentLegal(BComponent parent)
  {
    return parent instanceof BSmsNetwork || parent instanceof BSmsRuleFolder;
  }

  @Override
  public BIcon getIcon() { return ICON; }
  private static final BIcon ICON = BIcon.std("folder.png");
}
