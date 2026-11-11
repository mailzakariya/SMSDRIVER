/*
 * Copyright (c) Tecum. All Rights Reserved.
 */
package com.tecum.sms.core;

import java.util.Map;

/**
 * Message templates with {name} placeholders. Unknown placeholders are left
 * untouched so a typo is visible in the delivered message.
 */
public final class Templates
{
  private Templates() {}

  public static String render(String template, Map<String, String> values)
  {
    if (template == null) return "";
    StringBuilder sb = new StringBuilder(template.length() + 32);
    int i = 0;
    while (i < template.length())
    {
      char c = template.charAt(i);
      if (c == '{')
      {
        int end = template.indexOf('}', i + 1);
        if (end > i + 1)
        {
          String key = template.substring(i + 1, end);
          String v = values.get(key);
          if (v != null) { sb.append(v); i = end + 1; continue; }
        }
      }
      sb.append(c);
      i++;
    }
    return sb.toString();
  }

  /** Trim to a maximum length, adding an ellipsis when cut. 0 = unlimited. */
  public static String limit(String text, int maxChars)
  {
    if (maxChars <= 0 || text.length() <= maxChars) return text;
    if (maxChars <= 3) return text.substring(0, maxChars);
    return text.substring(0, maxChars - 3) + "...";
  }
}
