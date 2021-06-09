/*
 * Copyright 2002-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.orm.hibernate5.support;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hibernate.FlushMode;
import org.hibernate.HibernateException;
import org.hibernate.Session;
import org.hibernate.SessionFactory;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.lang.Nullable;
import org.springframework.orm.hibernate5.SessionFactoryUtils;
import org.springframework.orm.hibernate5.SessionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.ui.ModelMap;
import org.springframework.util.Assert;
import org.springframework.web.context.request.AsyncWebRequestInterceptor;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.context.request.async.CallableProcessingInterceptor;
import org.springframework.web.context.request.async.WebAsyncManager;
import org.springframework.web.context.request.async.WebAsyncUtils;

/**
 * Spring web request interceptor that binds a Hibernate {@code Session} to the
 * thread for the entire processing of the request.
 *
 * <p>This class is a concrete expression of the "Open Session in View" pattern, which
 * is a pattern that allows for the lazy loading of associations in web views despite
 * the original transactions already being completed.
 *
 * <p>This interceptor makes Hibernate Sessions available via the current thread,
 * which will be autodetected by transaction managers. It is suitable for service layer
 * transactions via {@link org.springframework.orm.hibernate5.HibernateTransactionManager}
 * as well as for non-transactional execution (if configured appropriately).
 *
 * <p>In contrast to {@link OpenSessionInViewFilter}, this interceptor is configured
 * in a Spring application context and can thus take advantage of bean wiring.
 *
 * <p><b>WARNING:</b> Applying this interceptor to existing logic can cause issues
 * that have not appeared before, through the use of a single Hibernate
 * {@code Session} for the processing of an entire request. In particular, the
 * reassociation of persistent objects with a Hibernate {@code Session} has to
 * occur at the very beginning of request processing, to avoid clashes with already
 * loaded instances of the same objects.
 *
 * @author Juergen Hoeller
 * @since 4.2
 * @see OpenSessionInViewFilter
 * @see OpenSessionInterceptor
 * @see org.springframework.orm.hibernate5.HibernateTransactionManager
 * @see TransactionSynchronizationManager
 * @see SessionFactory#getCurrentSession()
 */
public class OpenSessionInViewInterceptor implements AsyncWebRequestInterceptor {

	/**
	 * Suffix that gets appended to the {@code SessionFactory}
	 * {@code toString()} representation for the "participate in existing
	 * session handling" request attribute.
	 *
	 * 后缀
	 * @see #getParticipateAttributeName
	 */
	public static final String PARTICIPATE_SUFFIX = ".PARTICIPATE";

	protected final Log logger = LogFactory.getLog(getClass());

	/**
	 * session 工厂
	 */
	@Nullable
	private SessionFactory sessionFactory;

	/**
	 * Return the Hibernate SessionFactory that should be used to create Hibernate Sessions.
	 */
	@Nullable
	public SessionFactory getSessionFactory() {
		return this.sessionFactory;
	}

	/**
	 * Set the Hibernate SessionFactory that should be used to create Hibernate Sessions.
	 */
	public void setSessionFactory(@Nullable SessionFactory sessionFactory) {
		this.sessionFactory = sessionFactory;
	}

	private SessionFactory obtainSessionFactory() {
		SessionFactory sf = getSessionFactory();
		Assert.state(sf != null, "No SessionFactory set");
		return sf;
	}


	/**
	 * Open a new Hibernate {@code Session} according and bind it to the thread via the
	 * {@link TransactionSynchronizationManager}.
	 */
	@Override
	public void preHandle(WebRequest request) throws DataAccessException {
		// 获取key ,key的组装方式是 SessionFactory.toString + .PARTICIPATE
		String key = getParticipateAttributeName();
		// 获取异步管理器
		WebAsyncManager asyncManager = WebAsyncUtils.getAsyncManager(request);
		// 存在结果 并且绑定成功
		if (asyncManager.hasConcurrentResult() && applySessionBindingInterceptor(asyncManager, key)) {
			return;
		}

		// 事务管理器中存在当前session工厂对应的资源
		if (TransactionSynchronizationManager.hasResource(obtainSessionFactory())) {
			// Do not modify the Session: just mark the request accordingly.
			// 获取count属性
			Integer count = (Integer) request.getAttribute(key, WebRequest.SCOPE_REQUEST);
			// 确定count的具体数据
			int newCount = (count != null ? count + 1 : 1);
			// 设置 count 数据给request
			request.setAttribute(getParticipateAttributeName(), newCount, WebRequest.SCOPE_REQUEST);
		}
		else {
			logger.debug("Opening Hibernate Session in OpenSessionInViewInterceptor");
			// 开启session
			Session session = openSession();
			// 创建 session 持有器
			SessionHolder sessionHolder = new SessionHolder(session);
			// 进行资源绑定
			TransactionSynchronizationManager.bindResource(obtainSessionFactory(), sessionHolder);

			// 创建请求拦截器
			AsyncRequestInterceptor asyncRequestInterceptor =
					new AsyncRequestInterceptor(obtainSessionFactory(), sessionHolder);
			// 网络管理器进行注册
			asyncManager.registerCallableInterceptor(key, asyncRequestInterceptor);
			asyncManager.registerDeferredResultInterceptor(key, asyncRequestInterceptor);
		}
	}

	@Override
	public void postHandle(WebRequest request, @Nullable ModelMap model) {
	}

	/**
	 * Unbind the Hibernate {@code Session} from the thread and close it).
	 * @see TransactionSynchronizationManager
	 */
	@Override
	public void afterCompletion(WebRequest request, @Nullable Exception ex) throws DataAccessException {
		// 减少计数项
		if (!decrementParticipateCount(request)) {
			// 获取session持有对象
			SessionHolder sessionHolder =
					(SessionHolder) TransactionSynchronizationManager.unbindResource(obtainSessionFactory());
			logger.debug("Closing Hibernate Session in OpenSessionInViewInterceptor");
			// 关闭session
			SessionFactoryUtils.closeSession(sessionHolder.getSession());
		}
	}

	private boolean decrementParticipateCount(WebRequest request) {
		// 获取属性, key
		String participateAttributeName = getParticipateAttributeName();
		// 获取 count 属性
		Integer count = (Integer) request.getAttribute(participateAttributeName, WebRequest.SCOPE_REQUEST);
		// 为空则返回false
		if (count == null) {
			return false;
		}
		// Do not modify the Session: just clear the marker.
		// 大于1时的处理
		if (count > 1) {
			request.setAttribute(participateAttributeName, count - 1, WebRequest.SCOPE_REQUEST);
		}
		else {
			request.removeAttribute(participateAttributeName, WebRequest.SCOPE_REQUEST);
		}
		return true;
	}

	@Override
	public void afterConcurrentHandlingStarted(WebRequest request) {
		if (!decrementParticipateCount(request)) {
			TransactionSynchronizationManager.unbindResource(obtainSessionFactory());
		}
	}

	/**
	 * Open a Session for the SessionFactory that this interceptor uses.
	 * <p>The default implementation delegates to the {@link SessionFactory#openSession}
	 * method and sets the {@link Session}'s flush mode to "MANUAL".
	 * @return the Session to use
	 * @throws DataAccessResourceFailureException if the Session could not be created
	 * @see FlushMode#MANUAL
	 */
	@SuppressWarnings("deprecation")
	protected Session openSession() throws DataAccessResourceFailureException {
		try {
			Session session = obtainSessionFactory().openSession();
			session.setFlushMode(FlushMode.MANUAL);
			return session;
		}
		catch (HibernateException ex) {
			throw new DataAccessResourceFailureException("Could not open Hibernate Session", ex);
		}
	}

	/**
	 * Return the name of the request attribute that identifies that a request is
	 * already intercepted.
	 * <p>The default implementation takes the {@code toString()} representation
	 * of the {@code SessionFactory} instance and appends {@link #PARTICIPATE_SUFFIX}.
	 */
	protected String getParticipateAttributeName() {
		return obtainSessionFactory() + PARTICIPATE_SUFFIX;
	}

	private boolean applySessionBindingInterceptor(WebAsyncManager asyncManager, String key) {
		CallableProcessingInterceptor cpi = asyncManager.getCallableInterceptor(key);
		if (cpi == null) {
			return false;
		}
		((AsyncRequestInterceptor) cpi).bindSession();
		return true;
	}

}
