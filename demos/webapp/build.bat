@echo off
REM
REM  Copyright (c) 1997, 2019 Oracle and/or its affiliates. All rights reserved.
REM
REM  This program and the accompanying materials are made available under the
REM  terms of the Eclipse Public License v. 2.0, which is available at
REM  http://www.eclipse.org/legal/epl-2.0.
REM
REM  This Source Code may also be made available under the following Secondary
REM  Licenses when the conditions for such availability set forth in the
REM  Eclipse Public License v. 2.0 are satisfied: GNU General Public License,
REM  version 2 with the GNU Classpath Exception, which is available at
REM  https://www.gnu.org/software/classpath/license.html.
REM
REM  SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
REM
mkdir src\docroot\WEB-INF\classes
mkdir src\docroot\WEB-INF\classes\demo
mkdir src\docroot\WEB-INF\lib
cd src\classes
echo compiling classes directory
javac -d ..\docroot\WEB-INF\classes demo\*.java
cd ..\taglib
echo compiling lib directory
javac -classpath "..\docroot\WEB-INF\classes;%CLASSPATH%" demo\*.java
echo creating tag library archive
jar cvf ..\docroot\WEB-INF\lib\taglib.jar META-INF demo\*.class
del demo\*.class
cd ..\docroot
echo creating web archive
jar cvf ..\..\jakartamail.war index.html *.jsp WEB-INF
cd WEB-INF\classes\demo
del *.*
cd ..
rmdir demo
cd ..
rmdir classes
cd lib
del *.*
cd ..
rmdir lib
cd ..\..\..
