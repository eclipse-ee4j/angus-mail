<%--

    Copyright (c) 2001, 2023 Oracle and/or its affiliates. All rights reserved.

    This program and the accompanying materials are made available under the
    terms of the Eclipse Distribution License v. 1.0, which is available at
    http://www.eclipse.org/org/documents/edl-v10.php.

    SPDX-License-Identifier: BSD-3-Clause

--%>

<%@ page language="java" import="demo.MailUserBean" %>
<%@ page errorPage="errorpage.jsp" %>
<jsp:useBean id="mailuser" scope="session" class="demo.MailUserBean"/>

<html>
<head>
    <title>Jakarta Mail login</title>
</head>

<body bgcolor="white">

<%
    mailuser.login(request.getParameter("hostname"),
            request.getParameter("username"),
            request.getParameter("password"));
    session.setAttribute("folder", mailuser.getFolder());
%>

<jsp:forward page="folders.jsp"/>

</body>
</html>

