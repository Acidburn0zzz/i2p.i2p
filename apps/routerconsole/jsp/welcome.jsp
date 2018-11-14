<%@page contentType="text/html"%><%@page pageEncoding="UTF-8"%><jsp:useBean class="net.i2p.router.web.helpers.WizardHelper" id="wizhelper" scope="request" /><%
    // page ID
    final int LAST_PAGE = 7;
    String pg = request.getParameter("page");
    int ipg;
    if (pg == null) {
        ipg = 1;
    } else {
        try {
            ipg = Integer.parseInt(pg);
            if (request.getParameter("prev") != null) {
                // previous button handling
                if (ipg == 6)
                    ipg = 3;
                else
                    ipg -= 2;
            }
            if (ipg <= 0 || ipg > LAST_PAGE) {
                ipg = 1;
            } else if (ipg == 4 && request.getParameter("skipbw") != null) {
                ipg++;  // skip bw test
            }
        } catch (NumberFormatException nfe) {
            ipg = 1;
        }
    }

    // detect completion
    boolean done = request.getParameter("done") != null || request.getParameter("skip") != null;
    if (done) {
        // tell wizard helper we're done
        String i2pcontextId = request.getParameter("i2p.contextId");
        try {
            if (i2pcontextId != null) {
                session.setAttribute("i2p.contextId", i2pcontextId);
            } else {
                i2pcontextId = (String) session.getAttribute("i2p.contextId");
            }
        } catch (IllegalStateException ise) {}
        wizhelper.setContextId(i2pcontextId);
        wizhelper.complete();

        // redirect to /home
        response.setStatus(307);
        response.setHeader("Cache-Control","no-cache");
        String req = request.getRequestURL().toString();
        int slash = req.indexOf("/welcome");
        if (slash >= 0)
            req = req.substring(0, slash) + "/home";
        else // shouldn't happen
            req = "http://127.0.0.1:7657/home";
        response.setHeader("Location", req);
        // force commitment
        response.getOutputStream().close();
        return;
    }
%><!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">
<html><head>
<%@include file="css.jsi" %>
<%=intl.title("New Install Wizard")%>
<%
    if (ipg == 4) {
%>
<script src="/js/ajax.js" type="text/javascript"></script>
<script type="text/javascript">
  var failMessage = "<hr><b><%=intl._t("Router is down")%><\/b>";
  function requestAjax1() { ajax("/welcomexhr.jsp", "xhr", "1000"); }
  function initAjax() { setTimeout(requestAjax1, "1000");  }
</script>
<%
    }
%>
</head>
<%
    if (ipg == 4) {
%>
<body onload="initAjax()">
<%
    } else {
%>
<body>
<%
    }
%>
<h2><%=intl._t("New Install Wizard")%> <%=ipg%>/<%=LAST_PAGE%></h2>
<div id="wizard">
<%--
    // note that for the handler we use a session scope, not a page scope,
    // so that we can access the NDT test results.
    // The MLabHelper singleton will prevent multiple simultaneous tests, even across sessions.
--%>
<jsp:useBean class="net.i2p.router.web.helpers.WizardHandler" id="formhandler" scope="session" />
<%@include file="formhandler.jsi" %>
<form action="" method="POST">
<input type="hidden" name="nonce" value="<%=pageNonce%>">
<input type="hidden" name="action" value="blah" >
<input type="hidden" name="page" value="<%=(ipg + 1)%>" >
<%

    if (ipg == 1) {
        // language selection
%>
<jsp:useBean class="net.i2p.router.web.helpers.ConfigUIHelper" id="uihelper" scope="request" />
<jsp:setProperty name="uihelper" property="contextId" value="<%=i2pcontextId%>" />
<%-- needed for CSS: --%><div id="config_ui">
<%-- needed for lang setting in css.jsi: --%><input type="hidden" name="consoleNonce" value="<%=net.i2p.router.web.CSSHelper.getNonce()%>" >
<h3 id="langheading"><%=uihelper._t("Router Console Language")%></h3>
<div id="langsettings">
<jsp:getProperty name="uihelper" property="langSettings" />
</div></div>
<%

    } else if (ipg == 2) {
        // I2P Philosophy
        // license info?
%>
<h3><%=intl._t("Why I2P?")%></h3>
<p>TODO</p>
<%

    } else if (ipg == 3) {
        // Overview of bandwidth test
%>
<h3><%=intl._t("Bandwidth Test")%></h3>
<p>
<%=intl._t("I2P will now test your internet connection to identify the optimal speed settings.")%>
<%=intl._t("Applying these settings will maximize your download speed using the third-party M-Lab service.")%>
<%=intl._t("M-Lab collects and publishes the IP address of the client that conducted the measurement.")%>
<%=intl._t("FIXME! This is necessary to understand and describe experimental results, including to identify which Internet service provider the test was conducted from.")%>
<%=intl._t("Please review the M-Lab privacy policies linked below.")%>
<%=intl._t("If you do not wish to run the M-Lab bandwidth test, you may skip it by clicking the button below.")%>
</p><p>
<a href="https://www.measurementlab.net/privacy/" target="_blank"><%=intl._t("M-Lab Privacy Policy")%></a>
<br><a href="https://github.com/m-lab/mlab-ns/blob/master/MLAB-NS_PRIVACY_POLICY.md" target="_blank"><%=intl._t("M-Lab Name Server Privacy Policy")%></a>
</p>


<%

    } else if (ipg == 4) {
        // Bandwidth test in progress (w/ AJAX)
%>
<h3><%=intl._t("Bandwidth Test in Progress")%></h3>
<p>TODO</p>
<%

    } else if (ipg == 5) {
        // Bandwidth test results
        // and/or manual bw entry?
%>
<jsp:useBean class="net.i2p.router.web.helpers.ConfigNetHelper" id="nethelper" scope="request" />
<jsp:setProperty name="nethelper" property="contextId" value="<%=i2pcontextId%>" />
<h3><%=intl._t("Bandwidth Test Results")%></h3>
<table class="configtable">
<tr><td>
Test results go here
</td></tr>
</table>
<h3><%=intl._t("Bandwidth Configuration")%></h3>
<table id="bandwidthconfig" class="configtable">
<tr><td class="infohelp" colspan="2">
<%=intl._t("I2P will work best if you configure your rates to match the speed of your internet connection.")%>
</td></tr>
<tr><td><input style="text-align: right; width: 5em;" name="inboundrate" type="text" size="5" maxlength="5" value="<jsp:getProperty name="nethelper" property="inboundBurstRate" />" >
<%=intl._t("KBps In")%>
</td><td>(<jsp:getProperty name="nethelper" property="inboundBurstRateBits" />)</td>
</tr><tr>
<%-- display burst, set standard, handler will fix up --%>
<td><input style="text-align: right; width: 5em;" name="outboundrate" type="text" size="5" maxlength="5" value="<jsp:getProperty name="nethelper" property="outboundBurstRate" />" >
<%=intl._t("KBps Out")%>
</td><td>(<jsp:getProperty name="nethelper" property="outboundBurstRateBits" />)</td>
</tr><tr>
<td><jsp:getProperty name="nethelper" property="sharePercentageBox" /> <%=intl._t("Share")%></td>
<td>(<jsp:getProperty name="nethelper" property="shareRateBits" />)
</td></tr>
<tr><td class="infohelp" colspan="2">
<% int share = Math.round(nethelper.getShareBandwidth() * 1.024f);
    if (share < 12) {
        out.print("<b>");
        out.print(intl._t("NOTE"));
        out.print("</b>: ");
        out.print(intl._t("You have configured I2P to share only {0} KBps.", share));
        out.print("\n");

        out.print(intl._t("I2P requires at least 12KBps to enable sharing. "));
        out.print(intl._t("Please enable sharing (participating in tunnels) by configuring more bandwidth. "));
        out.print(intl._t("It improves your anonymity by creating cover traffic, and helps the network."));
    } else {
        out.print(intl._t("You have configured I2P to share {0} KBps.", share));
        out.print("\n");

        out.print(intl._t("The higher the share bandwidth the more you improve your anonymity and help the network."));
    }
%></td></tr>
</table>
<%

    } else if (ipg == 6) {
        // Browser setup
%>
<h3><%=intl._t("Browser Setup")%></h3>
<p>TODO</p>
<%

    } else if (ipg == LAST_PAGE) {
        // Done
%>
<h3><%=intl._t("Finished")%></h3>
<p>TODO</p>
<%

    } else {
%>
<p>unknown wizard page</p>
<%
    }
%>
<div class="wizardbuttons">
<table class="configtable"><tr><td class="optionsave">
<%
    if (ipg != 1) {
%>
<input type="submit" class="go" name="prev" value="<%=intl._t("Previous")%>" >
<%
    }
    if (ipg != LAST_PAGE) {
%>
<input type="submit" class="cancel" name="skip" value="<%=intl._t("Skip Setup")%>" >
<%
        if (ipg == 3) {
%>
<input type="submit" class="cancel" name="skipbw" value="<%=intl._t("Skip Bandwidth Test")%>" >
<%
        } else if (ipg == 4) {
%>
<input type="submit" class="cancel" name="cancelbw" value="<%=intl._t("Cancel Bandwidth Test")%>" >
<%
        }
%>
<input type="submit" class="go" name="next" value="<%=intl._t("Next")%>" >
<%
    } else {
%>
<input type="submit" class="go" name="done" value="<%=intl._t("Finished")%>" >
<%
    }
%>
</td></tr></table>
</form>
</div></body></html>
