Memory Leak demonstration using 'Standalone JTA with Hibernate example'.
==================================================================================================
Author: Gytis Trikleris; Ronald Mik
Level: Advanced;
Technologies: JTA, CDI, JPA

What is it?
-----------

This is an example demonstrating a memory leak based on the JTA with Hibernate example made by Gytis Trikleris. The relating issue is: https://hibernate.atlassian.net/browse/HHH-12448


System requirements
-------------------

To build this project you need Java 8 (Java SDK 1.8) or better and Maven 3.3.3 or better.


Usage
-------------------------------

mvn clean exec:java
OR
run main in QuickStartApplication

This example executes the following steps:

    1. Does some work and flushes it. This will cause Envers to register a beforeCompletion and afterCompletion.
    2. Register a beforeCompletion listener that will wait until the transaction has been rolled back (by the transaction reaper)
    3. Print the size of the Map 'auditProcesses' in AuditProcessManager. This should be 0, yet it is 1 due to the memory leak. This is causeed by the fact that afterCompletion is already called by the reaper thread. The reaper thread registered a delayedAfterCompletion, which the 'normal' thread will normally execute. Yet because of locking in TwoPhaseCoordinator the delayedAfterCompletion has not yet been set by the reaper thread.
