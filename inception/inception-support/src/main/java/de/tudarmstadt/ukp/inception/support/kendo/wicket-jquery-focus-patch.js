/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*global DOMParser: true, ActiveXObject: true, console: true */

/*
 * Wicket Ajax Support
 *
 * WEBANNO: This file reproduces some JS code from wicket-ajax-jquery.js and introduces 
 *          fixes for handling the focus on specific Kendo components.
 *
 * @author Igor Vaynberg
 * @author Matej Knopp
 */


Wicket.Focus.requestFocus = function() {
  // if the focused component is replaced by the ajax response, a re-focus might be needed
  // (if focus was not changed from server) but if not, and the focus component should
  // remain the same, do not re-focus - fixes problem on IE6 for combos that have
  // the popup open (refocusing closes popup)
  var WF = Wicket.Focus;
  if (WF.refocusLastFocusedComponentAfterResponse && WF.lastFocusId) {
    var toFocus = Wicket.$(WF.lastFocusId);

    if (toFocus) {
      Wicket.Log.info("Calling focus on '%s'", WF.lastFocusId);

      var safeFocus = function() {
        try {
          // BEGIN WEBANNO - #722
          // Some Kendo components turn the original input which carries the wicket
          // ID into a hidden input element which cannot be focused - we need to 
          // handle these specially.
          // toFocus.focus();
          if ($(toFocus).is( ":data('kendoComboBox')" )) {
            $(toFocus).data("kendoComboBox").input.focus();
          }
          if ($(toFocus).is( ":data('kendoNumericTextBox')" )) {
            $(toFocus).data("kendoNumericTextBox").focus();
          }
          else {
            toFocus.focus();
          }
          // END WEBANNO - #722
        } catch (ignore) {
          // WICKET-6209 IE fails if toFocus is disabled
        }
      };

      if (WF.focusSetFromServer) {
        // WICKET-5858
        window.setTimeout(safeFocus, 0);
      } else {
        // avoid loops like - onfocus triggering an event the modifies the tag => refocus => the event is triggered again
        var temp = toFocus.onfocus;
        toFocus.onfocus = null;

        // IE needs setTimeout (it seems not to call onfocus sync. when focus() is called
        window.setTimeout(function () { safeFocus(); toFocus.onfocus = temp; }, 0);
      }
    } else {
      WF.lastFocusId = "";
      Wicket.Log.info("Couldn't set focus on element with id '%s' because it is not in the page anymore", WF.lastFocusId);
    }
  } else if (WF.refocusLastFocusedComponentAfterResponse) {
    Wicket.Log.info("last focus id was not set");
  } else {
    Wicket.Log.info("refocus last focused component not needed/allowed");
  }
  Wicket.Focus.refocusLastFocusedComponentAfterResponse = false;
}

Wicket.Event.remove(window, 'focusin', Wicket.Focus.focusin);

Wicket.Focus.focusin = function (event) {
  event = Wicket.Event.fix(event);

  var target = event.target;
  if (target) {
    var WF = Wicket.Focus;
    WF.refocusLastFocusedComponentAfterResponse = false;
    // BEGIN WEBANNO - #722
    // Some Kendo components turn the original input which carries the wicket
    // ID into a hidden input element which cannot be focused - we need to 
    // handle these specially by fetching the Wicket ID from the original input instead
    // of from the visible input.
    var widget = $(target).parent().next("[data-role='combobox']");
    if (widget && widget.length) {
      target = widget[0];
    }
    else {
      var widget2 = $(target).parent().next("[data-role='autocomplete']");
      if (widget2 && widget2.length) {
        target = widget2[0];
      }
    }
    // END WEBANNO - #722
    var id = target.id;
    WF.lastFocusId = id;
    Wicket.Log.info("focus set on '%s'", id);
  }
}

Wicket.Event.add(window, 'focusin', Wicket.Focus.focusin);

