/*
 * Copyright (c) Tecum. All Rights Reserved.
 */
package com.tecum.sms;

import javax.baja.sys.BFrozenEnum;
import javax.baja.sys.Sys;
import javax.baja.sys.Type;

import com.tecum.sms.core.RuleEvaluator;

/**
 * Condition that activates an SMS point rule. Ordinals match
 * {@link RuleEvaluator} constants.
 */
public final class BSmsCondition extends BFrozenEnum
{
  public static final int VALUE_EQUALS = RuleEvaluator.VALUE_EQUALS;
  public static final int VALUE_NOT_EQUALS = RuleEvaluator.VALUE_NOT_EQUALS;
  public static final int GREATER_THAN = RuleEvaluator.GREATER_THAN;
  public static final int LESS_THAN = RuleEvaluator.LESS_THAN;
  public static final int GREATER_OR_EQUAL = RuleEvaluator.GREATER_OR_EQUAL;
  public static final int LESS_OR_EQUAL = RuleEvaluator.LESS_OR_EQUAL;
  public static final int OUTSIDE_RANGE = RuleEvaluator.OUTSIDE_RANGE;
  public static final int INSIDE_RANGE = RuleEvaluator.INSIDE_RANGE;
  public static final int IS_TRUE = RuleEvaluator.IS_TRUE;
  public static final int IS_FALSE = RuleEvaluator.IS_FALSE;
  public static final int ANY_CHANGE = RuleEvaluator.ANY_CHANGE;
  public static final int STATUS_ALARM = RuleEvaluator.STATUS_ALARM;
  public static final int STATUS_FAULT = RuleEvaluator.STATUS_FAULT;
  public static final int STATUS_DOWN = RuleEvaluator.STATUS_DOWN;
  public static final int STATUS_OVERRIDDEN = RuleEvaluator.STATUS_OVERRIDDEN;
  public static final int STATUS_NOT_OK = RuleEvaluator.STATUS_NOT_OK;

  public static final BSmsCondition valueEquals = new BSmsCondition(VALUE_EQUALS);
  public static final BSmsCondition valueNotEquals = new BSmsCondition(VALUE_NOT_EQUALS);
  public static final BSmsCondition greaterThan = new BSmsCondition(GREATER_THAN);
  public static final BSmsCondition lessThan = new BSmsCondition(LESS_THAN);
  public static final BSmsCondition greaterOrEqual = new BSmsCondition(GREATER_OR_EQUAL);
  public static final BSmsCondition lessOrEqual = new BSmsCondition(LESS_OR_EQUAL);
  public static final BSmsCondition outsideRange = new BSmsCondition(OUTSIDE_RANGE);
  public static final BSmsCondition insideRange = new BSmsCondition(INSIDE_RANGE);
  public static final BSmsCondition isTrue = new BSmsCondition(IS_TRUE);
  public static final BSmsCondition isFalse = new BSmsCondition(IS_FALSE);
  public static final BSmsCondition anyChange = new BSmsCondition(ANY_CHANGE);
  public static final BSmsCondition statusAlarm = new BSmsCondition(STATUS_ALARM);
  public static final BSmsCondition statusFault = new BSmsCondition(STATUS_FAULT);
  public static final BSmsCondition statusDown = new BSmsCondition(STATUS_DOWN);
  public static final BSmsCondition statusOverridden = new BSmsCondition(STATUS_OVERRIDDEN);
  public static final BSmsCondition statusNotOk = new BSmsCondition(STATUS_NOT_OK);

  public static final BSmsCondition DEFAULT = greaterThan;

  public static BSmsCondition make(int ordinal)
  {
    return (BSmsCondition) valueEquals.getRange().get(ordinal, false);
  }

  public static BSmsCondition make(String tag)
  {
    return (BSmsCondition) valueEquals.getRange().get(tag);
  }

  private BSmsCondition(int ordinal) { super(ordinal); }

  @Override
  public Type getType() { return TYPE; }
  public static final Type TYPE = Sys.loadType(BSmsCondition.class);
}
