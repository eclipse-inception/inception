/*
 * The jsoup code-base (include source and compiled packages) are distributed under the open source 
 * MIT license as described below.
 * 
 * The MIT License
 * Copyright © 2009 - 2016 Jonathan Hedley (jonathan@hedley.net)
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
 * associated documentation files (the "Software"), to deal in the Software without restriction,
 * including without limitation the rights to use, copy, modify, merge, publish, distribute,
 * sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in all copies or
 * substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT
 * NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
 * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package de.tudarmstadt.ukp.inception.io.html.dkprocore.internal;

import org.jsoup.internal.StringUtil;
import org.jsoup.nodes.CDataNode;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

/**
 * Methods in this class were copied from JSoup. They were private and could not be accessed. Last
 * verified against jsoup 1.24.1.
 */
public final class JSoupUtil
{
    // Source:
    // https://github.com/jhy/jsoup/blob/jsoup-1.24.1/src/main/java/org/jsoup/nodes/TextNode.java#L117
    public static boolean lastCharIsWhitespace(CharSequence sb)
    {
        return sb.length() != 0 && sb.charAt(sb.length() - 1) == ' ';
    }

    // Source:
    // https://github.com/jhy/jsoup/blob/jsoup-1.24.1/src/main/java/org/jsoup/nodes/Element.java#L1690
    public static void appendNormalisedText(StringBuilder accum, TextNode textNode)
    {
        String text = textNode.getWholeText();

        if (preserveWhitespace(textNode.parentNode()) || textNode instanceof CDataNode) {
            accum.append(text);
        }
        else {
            StringUtil.appendNormalisedWhitespace(accum, text, lastCharIsWhitespace(accum));
        }
    }

    // Source:
    // https://github.com/jhy/jsoup/blob/jsoup-1.24.1/src/main/java/org/jsoup/nodes/Element.java#L1698
    public static boolean preserveWhitespace(Node node)
    {
        // looks only at this element and five levels up, to prevent recursion & needless stack
        // searches
        if (node instanceof Element) {
            Element el = (Element) node;
            int i = 0;
            do {
                if (el.tag().preserveWhitespace()) {
                    return true;
                }
                el = el.parent();
                i++;
            }
            while (i < 6 && el != null);
        }
        return false;
    }
}
