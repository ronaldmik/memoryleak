/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2013, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.narayana.quickstart.jta;

import javax.inject.Inject;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.SynchronizationType;
import javax.transaction.HeuristicMixedException;
import javax.transaction.HeuristicRollbackException;
import javax.transaction.NotSupportedException;
import javax.transaction.RollbackException;
import javax.transaction.Status;
import javax.transaction.SystemException;
import javax.transaction.TransactionManager;

import java.lang.reflect.Field;
import java.util.Map;

import org.hibernate.Transaction;
import org.hibernate.envers.boot.internal.EnversService;
import org.hibernate.envers.internal.synchronization.AuditProcess;
import org.hibernate.envers.internal.synchronization.AuditProcessManager;
import org.hibernate.internal.SessionFactoryImpl;
import org.hibernate.internal.SessionImpl;

import com.arjuna.ats.arjuna.common.arjPropertyManager;
import com.arjuna.ats.arjuna.coordinator.TransactionReaper;

/**
 * @author <a href="mailto:gytis@redhat.com">Gytis Trikleris</a>
 */
public class QuickstartService {

    @Inject
    private EntityManagerFactory entityManagerFactory;

    private TransactionManager transactionManager;

    public QuickstartService() throws NamingException {
        transactionManager = InitialContext.doLookup("java:/TransactionManager");
    }

    public void demonstrateMemoryLeak() throws Exception {
        arjPropertyManager.getCoordinatorEnvironmentBean().setTxReaperCancelWaitPeriod(300000L);
        TransactionReaper.instantiate();

        TransactionalWorker transactionalWorker = new TransactionalWorker();
        try {
            new Thread(transactionalWorker).start();
            synchronized (transactionalWorker._synch) {
                transactionalWorker._synch.wait();
            }
        } finally {
            TransactionReaper.terminate(true);
        }
        printAuditProcessesInMemory();
    }

    class TransactionalWorker implements Runnable {
        final Object _synch = new Object();

        public void run() {
            EntityManager entityManager = null;
            try {
                System.out.println("Starting transactional work");
                transactionManager.setTransactionTimeout(5);
                transactionManager.begin();

                // Get entityManager, persist and flush to make sure envers registers onCompletionListeners
                entityManager = entityManagerFactory.createEntityManager(SynchronizationType.SYNCHRONIZED);
                entityManager.persist(new QuickstartEntity("My auditing will live in the auditProcesses in AuditProcessManager forever"));
                entityManager.flush();

                // Add extra onCompletionListeners like envers does
                registerPrintSomethingAfterCompletion(entityManager);
                registerWaitForRollbackBeforeCompletion(entityManager, transactionManager);
                transactionManager.commit();
            } catch (RollbackException e) {
                System.out.println("RollbackException was expected");
            } catch (HeuristicMixedException | SystemException | HeuristicRollbackException | NotSupportedException e) {
                throw new IllegalStateException("An unexpected exception occured", e);
            } finally {
                try {
                    entityManager.close();
                } finally {
                    synchronized (_synch) {
                        _synch.notify();
                    }
                }
            }
        }

        private void registerWaitForRollbackBeforeCompletion(EntityManager entityManager, TransactionManager transactionManager) {
            SessionImpl session = entityManager.unwrap(SessionImpl.class);
            session.getActionQueue().registerProcess(session1 -> {
                try {
                    System.out.println("Start before completion");
                    while (transactionManager.getTransaction().getStatus() != Status.STATUS_ROLLEDBACK) {
                        Thread.sleep(10L);
                    }
                } catch (Throwable e) {
                    // nothing
                }
            });
        }

        private void registerPrintSomethingAfterCompletion(EntityManager entityManager) {
            SessionImpl session = entityManager.unwrap(SessionImpl.class);
            session.getActionQueue().registerProcess(
                    (success, session1) -> System.out
                            .println("I will not be printed! My companion from AuditProcessManager wil not clean up some work!"));
        }
    }

    private void printAuditProcessesInMemory() {
        SessionFactoryImpl sessionFactory = ((SessionFactoryImpl) entityManagerFactory);
        EnversService enversService = sessionFactory.getServiceRegistry().getService(EnversService.class);
        AuditProcessManager auditProcessManager = enversService.getAuditProcessManager();
        Map<Transaction, AuditProcess> auditProcesses = getAuditProcesses(auditProcessManager);
        if (auditProcesses == null) {
            System.out.println("Error getting auditProcesses");
        } else {
            System.out.println(String.format("Size of auditProcesses (expected: 0): %s", auditProcesses.size()));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<Transaction, AuditProcess> getAuditProcesses(AuditProcessManager auditProcessManager) {
        try {
            Field f = auditProcessManager.getClass().getDeclaredField("auditProcesses"); //NoSuchFieldException
            f.setAccessible(true);
            return (Map<Transaction, AuditProcess>) f.get(auditProcessManager);
        } catch (IllegalAccessException | NoSuchFieldException e) {
            e.printStackTrace();
        }
        return null;
    }
}
