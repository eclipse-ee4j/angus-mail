<%--

    Copyright (c) 2001, 2021 Oracle and/or its affiliates. All rights reserved.

    This program and the accompanying materials are made available under the
    terms of the Eclipse Distribution License v. 1.0, which is available at
    http://www.eclipse.org/org/documents/edl-v10.php.

    SPDX-License-Identifier: BSD-3-Clause

--%>

<%@ page isErrorPage="true" %>
<html>
<head>
<title>Jakarta Mail errorpage</title>
</head>
<body bgcolor="white">
<form ACTION="errordetails" METHOD=POST>
<% session.putValue("details", exception.toString()); %>
<h2>An error occured while attempting to perform the operation you requested.
</h2>
<input type="submit" name="Error Details" value="Error Details">
</body>
</html>

