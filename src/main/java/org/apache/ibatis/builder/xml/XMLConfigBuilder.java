/**
 *    Copyright 2009-2021 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.builder.xml;

import java.io.InputStream;
import java.io.Reader;
import java.util.Properties;

import javax.sql.DataSource;

import org.apache.ibatis.builder.BaseBuilder;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.datasource.DataSourceFactory;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.loader.ProxyFactory;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.io.VFS;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.mapping.DatabaseIdProvider;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.parsing.XPathParser;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.reflection.DefaultReflectorFactory;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.reflection.ReflectorFactory;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.reflection.wrapper.ObjectWrapperFactory;
import org.apache.ibatis.session.AutoMappingBehavior;
import org.apache.ibatis.session.AutoMappingUnknownColumnBehavior;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.transaction.TransactionFactory;
import org.apache.ibatis.type.JdbcType;

/**
 * @author Clinton Begin
 * @author Kazuki Shimizu
 */
public class XMLConfigBuilder extends BaseBuilder {

  /**
   * 是否已解析
   */
  private boolean parsed;
  /**
   * 基于 Java XPath 解析器
   */
  private final XPathParser parser;
  /**
   * 环境
   */
  private String environment;
  /**
   * ReflectorFactory 对象
   */
  private final ReflectorFactory localReflectorFactory = new DefaultReflectorFactory();

  public XMLConfigBuilder(Reader reader) {
    this(reader, null, null);
  }

  public XMLConfigBuilder(Reader reader, String environment) {
    this(reader, environment, null);
  }

  public XMLConfigBuilder(Reader reader, String environment, Properties props) {
    this(new XPathParser(reader, true, props, new XMLMapperEntityResolver()), environment, props);
  }

  public XMLConfigBuilder(InputStream inputStream) {
    this(inputStream, null, null);
  }

  public XMLConfigBuilder(InputStream inputStream, String environment) {
    this(inputStream, environment, null);
  }

  public XMLConfigBuilder(InputStream inputStream, String environment, Properties props) {
    this(new XPathParser(inputStream, true, props, new XMLMapperEntityResolver()), environment, props);
  }

  private XMLConfigBuilder(XPathParser parser, String environment, Properties props) {
    // 创建 Configuration 对象
    super(new Configuration());
    ErrorContext.instance().resource("SQL Mapper Configuration");
    // 设置 Configuration 的 variables 属性
    this.configuration.setVariables(props);
    this.parsed = false;
    this.environment = environment;
    this.parser = parser;
  }

  public Configuration parse() {
    // 若已解析，抛出 BuilderException 异常
    if (parsed) {
      throw new BuilderException("Each XMLConfigBuilder can only be used once.");
    }
    // 标记已解析
    parsed = true;
    // 解析 XML configuration 节点
    parseConfiguration(parser.evalNode("/configuration"));
    return configuration;
  }

  private void parseConfiguration(XNode root) {
    try {
      // issue #117 read properties first
      propertiesElement(root.evalNode("properties"));
      Properties settings = settingsAsProperties(root.evalNode("settings"));
      loadCustomVfs(settings);
      loadCustomLogImpl(settings);
      typeAliasesElement(root.evalNode("typeAliases"));
      pluginElement(root.evalNode("plugins"));
      objectFactoryElement(root.evalNode("objectFactory"));
      objectWrapperFactoryElement(root.evalNode("objectWrapperFactory"));
      reflectorFactoryElement(root.evalNode("reflectorFactory"));
      settingsElement(settings);
      // read it after objectFactory and objectWrapperFactory issue #631
      environmentsElement(root.evalNode("environments"));
      databaseIdProviderElement(root.evalNode("databaseIdProvider"));
      typeHandlerElement(root.evalNode("typeHandlers"));
      mapperElement(root.evalNode("mappers"));
    } catch (Exception e) {
      throw new BuilderException("Error parsing SQL Mapper Configuration. Cause: " + e, e);
    }
  }

  private Properties settingsAsProperties(XNode context) {
    // 将子标签，解析成 Properties 对象
    if (context == null) {
      return new Properties();
    }
    Properties props = context.getChildrenAsProperties();
    // Check that all settings are known to the configuration class
    // 校验每个属性，在 Configuration 中，有相应的 setting 方法，否则抛出 BuilderException 异常
    MetaClass metaConfig = MetaClass.forClass(Configuration.class, localReflectorFactory);
    for (Object key : props.keySet()) {
      if (!metaConfig.hasSetter(String.valueOf(key))) {
        throw new BuilderException("The setting " + key + " is not known.  Make sure you spelled it correctly (case sensitive).");
      }
    }
    return props;
  }

  private void loadCustomVfs(Properties props) throws ClassNotFoundException {
    // 获得 vfsImpl 属性
    String value = props.getProperty("vfsImpl");
    if (value != null) {
      // 使用 , 作为分隔符，拆成 VFS 类名的数组
      String[] clazzes = value.split(",");
      for (String clazz : clazzes) {
        if (!clazz.isEmpty()) {
          // 获得 VFS 类
          @SuppressWarnings("unchecked")
          Class<? extends VFS> vfsImpl = (Class<? extends VFS>)Resources.classForName(clazz);
          // 设置到 Configuration 中
          configuration.setVfsImpl(vfsImpl);
        }
      }
    }
  }

  private void loadCustomLogImpl(Properties props) {
    Class<? extends Log> logImpl = resolveClass(props.getProperty("logImpl"));
    configuration.setLogImpl(logImpl);
  }

  private void typeAliasesElement(XNode parent) {
    if (parent != null) {
      // 遍历子节点
      for (XNode child : parent.getChildren()) {
        // 指定为包的情况下，注册包下的每个类
        if ("package".equals(child.getName())) {
          String typeAliasPackage = child.getStringAttribute("name");
          configuration.getTypeAliasRegistry().registerAliases(typeAliasPackage);
          // 指定为类的情况下，直接注册类和别名
        } else {
          String alias = child.getStringAttribute("alias");
          String type = child.getStringAttribute("type");
          try {
            // 获得类是否存在
            Class<?> clazz = Resources.classForName(type);
            // 注册到 typeAliasRegistry 中
            if (alias == null) {
              typeAliasRegistry.registerAlias(clazz);
            } else {
              typeAliasRegistry.registerAlias(alias, clazz);
            }
            // 若类不存在，则抛出 BuilderException 异常
          } catch (ClassNotFoundException e) {
            throw new BuilderException("Error registering typeAlias for '" + alias + "'. Cause: " + e, e);
          }
        }
      }
    }
  }

  private void pluginElement(XNode parent) throws Exception {
    if (parent != null) {
      // 遍历 <plugins /> 标签
      for (XNode child : parent.getChildren()) {
        String interceptor = child.getStringAttribute("interceptor");
        Properties properties = child.getChildrenAsProperties();
        // 创建 Interceptor 对象，并设置属性
        Interceptor interceptorInstance = (Interceptor) resolveClass(interceptor).getDeclaredConstructor().newInstance();
        interceptorInstance.setProperties(properties);
        // 添加到 configuration 中
        configuration.addInterceptor(interceptorInstance);
      }
    }
  }

  private void objectFactoryElement(XNode context) throws Exception {
    if (context != null) {
      // 获得 ObjectFactory 的实现类
      String type = context.getStringAttribute("type");
      // 获得 Properties 属性
      Properties properties = context.getChildrenAsProperties();
      // 创建 ObjectFactory 对象，并设置 Properties 属性
      ObjectFactory factory = (ObjectFactory) resolveClass(type).getDeclaredConstructor().newInstance();
      factory.setProperties(properties);
      // 设置 Configuration 的 objectFactory 属性
      configuration.setObjectFactory(factory);
    }
  }

  private void objectWrapperFactoryElement(XNode context) throws Exception {
    if (context != null) {
      // 获得 ObjectFactory 的实现类
      String type = context.getStringAttribute("type");
      // 创建 ObjectWrapperFactory 对象
      ObjectWrapperFactory factory = (ObjectWrapperFactory) resolveClass(type).getDeclaredConstructor().newInstance();
      // 设置 Configuration 的 objectWrapperFactory 属性
      configuration.setObjectWrapperFactory(factory);
    }
  }

  private void reflectorFactoryElement(XNode context) throws Exception {
    if (context != null) {
      // 获得 ReflectorFactory 的实现类
      String type = context.getStringAttribute("type");
      // 创建 ReflectorFactory 对象
      ReflectorFactory factory = (ReflectorFactory) resolveClass(type).getDeclaredConstructor().newInstance();
      // 设置 Configuration 的 reflectorFactory 属性
      configuration.setReflectorFactory(factory);
    }
  }

  private void propertiesElement(XNode context) throws Exception {
    if (context != null) {
      // 读取子标签们，为 Properties 对象
      Properties defaults = context.getChildrenAsProperties();
      // 读取 resource 和 url 属性
      String resource = context.getStringAttribute("resource");
      String url = context.getStringAttribute("url");
      // resource 和 url 都存在的情况下，抛出 BuilderException 异常
      if (resource != null && url != null) {
        throw new BuilderException("The properties element cannot specify both a URL and a resource based property file reference.  Please specify one or the other.");
      }
      // 读取本地 Properties 配置文件到 defaults 中。
      if (resource != null) {
        defaults.putAll(Resources.getResourceAsProperties(resource));
        // 读取远程 Properties 配置文件到 defaults 中。
      } else if (url != null) {
        defaults.putAll(Resources.getUrlAsProperties(url));
      }
      // 覆盖 configuration 中的 Properties 对象到 defaults 中。
      Properties vars = configuration.getVariables();
      if (vars != null) {
        defaults.putAll(vars);
      }
      // 设置 defaults 到 parser 和 configuration 中。
      parser.setVariables(defaults);
      configuration.setVariables(defaults);
    }
  }

  private void settingsElement(Properties props) {
    configuration.setAutoMappingBehavior(AutoMappingBehavior.valueOf(props.getProperty("autoMappingBehavior", "PARTIAL")));
    configuration.setAutoMappingUnknownColumnBehavior(AutoMappingUnknownColumnBehavior.valueOf(props.getProperty("autoMappingUnknownColumnBehavior", "NONE")));
    configuration.setCacheEnabled(booleanValueOf(props.getProperty("cacheEnabled"), true));
    configuration.setProxyFactory((ProxyFactory) createInstance(props.getProperty("proxyFactory")));
    configuration.setLazyLoadingEnabled(booleanValueOf(props.getProperty("lazyLoadingEnabled"), false));
    configuration.setAggressiveLazyLoading(booleanValueOf(props.getProperty("aggressiveLazyLoading"), false));
    configuration.setMultipleResultSetsEnabled(booleanValueOf(props.getProperty("multipleResultSetsEnabled"), true));
    configuration.setUseColumnLabel(booleanValueOf(props.getProperty("useColumnLabel"), true));
    configuration.setUseGeneratedKeys(booleanValueOf(props.getProperty("useGeneratedKeys"), false));
    configuration.setDefaultExecutorType(ExecutorType.valueOf(props.getProperty("defaultExecutorType", "SIMPLE")));
    configuration.setDefaultStatementTimeout(integerValueOf(props.getProperty("defaultStatementTimeout"), null));
    configuration.setDefaultFetchSize(integerValueOf(props.getProperty("defaultFetchSize"), null));
    configuration.setDefaultResultSetType(resolveResultSetType(props.getProperty("defaultResultSetType")));
    configuration.setMapUnderscoreToCamelCase(booleanValueOf(props.getProperty("mapUnderscoreToCamelCase"), false));
    configuration.setSafeRowBoundsEnabled(booleanValueOf(props.getProperty("safeRowBoundsEnabled"), false));
    configuration.setLocalCacheScope(LocalCacheScope.valueOf(props.getProperty("localCacheScope", "SESSION")));
    configuration.setJdbcTypeForNull(JdbcType.valueOf(props.getProperty("jdbcTypeForNull", "OTHER")));
    configuration.setLazyLoadTriggerMethods(stringSetValueOf(props.getProperty("lazyLoadTriggerMethods"), "equals,clone,hashCode,toString"));
    configuration.setSafeResultHandlerEnabled(booleanValueOf(props.getProperty("safeResultHandlerEnabled"), true));
    configuration.setDefaultScriptingLanguage(resolveClass(props.getProperty("defaultScriptingLanguage")));
    configuration.setDefaultEnumTypeHandler(resolveClass(props.getProperty("defaultEnumTypeHandler")));
    configuration.setCallSettersOnNulls(booleanValueOf(props.getProperty("callSettersOnNulls"), false));
    configuration.setUseActualParamName(booleanValueOf(props.getProperty("useActualParamName"), true));
    configuration.setReturnInstanceForEmptyRow(booleanValueOf(props.getProperty("returnInstanceForEmptyRow"), false));
    configuration.setLogPrefix(props.getProperty("logPrefix"));
    configuration.setConfigurationFactory(resolveClass(props.getProperty("configurationFactory")));
    configuration.setShrinkWhitespacesInSql(booleanValueOf(props.getProperty("shrinkWhitespacesInSql"), false));
    configuration.setDefaultSqlProviderType(resolveClass(props.getProperty("defaultSqlProviderType")));
  }

  private void environmentsElement(XNode context) throws Exception {
    if (context != null) {
      // environment 属性非空，从 default 属性获得
      if (environment == null) {
        environment = context.getStringAttribute("default");
      }
      // 遍历 XNode 节点
      for (XNode child : context.getChildren()) {
        // 判断 environment 是否匹配
        String id = child.getStringAttribute("id");
        if (isSpecifiedEnvironment(id)) {
          // 解析 `<transactionManager />` 标签，返回 TransactionFactory 对象
          TransactionFactory txFactory = transactionManagerElement(child.evalNode("transactionManager"));
          // 解析 `<dataSource />` 标签，返回 DataSourceFactory 对象
          DataSourceFactory dsFactory = dataSourceElement(child.evalNode("dataSource"));
          DataSource dataSource = dsFactory.getDataSource();
          // 创建 Environment.Builder 对象
          Environment.Builder environmentBuilder = new Environment.Builder(id)
              .transactionFactory(txFactory)
              .dataSource(dataSource);
          // 构造 Environment 对象，并设置到 configuration 中
          configuration.setEnvironment(environmentBuilder.build());
          break;
        }
      }
    }
  }

  private void databaseIdProviderElement(XNode context) throws Exception {
    DatabaseIdProvider databaseIdProvider = null;
    if (context != null) {
      // 获得 DatabaseIdProvider 的类
      String type = context.getStringAttribute("type");
      // awful patch to keep backward compatibility
      if ("VENDOR".equals(type)) {
        type = "DB_VENDOR";
      }
      // 获得 Properties 对象
      Properties properties = context.getChildrenAsProperties();
      // 创建 DatabaseIdProvider 对象，并设置对应的属性
      databaseIdProvider = (DatabaseIdProvider) resolveClass(type).getDeclaredConstructor().newInstance();
      databaseIdProvider.setProperties(properties);
    }
    Environment environment = configuration.getEnvironment();
    if (environment != null && databaseIdProvider != null) {
      // 获得对应的 databaseId 编号
      String databaseId = databaseIdProvider.getDatabaseId(environment.getDataSource());
      // 设置到 configuration 中
      configuration.setDatabaseId(databaseId);
    }
  }

  private TransactionFactory transactionManagerElement(XNode context) throws Exception {
    if (context != null) {
      // 获得 TransactionFactory 的类
      String type = context.getStringAttribute("type");
      // 获得 Properties 属性
      Properties props = context.getChildrenAsProperties();
      // 创建 TransactionFactory 对象，并设置属性
      TransactionFactory factory = (TransactionFactory) resolveClass(type).getDeclaredConstructor().newInstance();
      factory.setProperties(props);
      return factory;
    }
    throw new BuilderException("Environment declaration requires a TransactionFactory.");
  }

  private DataSourceFactory dataSourceElement(XNode context) throws Exception {
    if (context != null) {
      // 获得 DataSourceFactory 的类
      String type = context.getStringAttribute("type");
      // 获得 Properties 属性
      Properties props = context.getChildrenAsProperties();
      // 创建 DataSourceFactory 对象，并设置属性
      DataSourceFactory factory = (DataSourceFactory) resolveClass(type).getDeclaredConstructor().newInstance();
      factory.setProperties(props);
      return factory;
    }
    throw new BuilderException("Environment declaration requires a DataSourceFactory.");
  }

  private void typeHandlerElement(XNode parent) {
    if (parent != null) {
      // 遍历子节点
      for (XNode child : parent.getChildren()) {
        // 如果是 package 标签，则扫描该包
        if ("package".equals(child.getName())) {
          String typeHandlerPackage = child.getStringAttribute("name");
          typeHandlerRegistry.register(typeHandlerPackage);
          // 如果是 typeHandler 标签，则注册该 typeHandler 信息
        } else {
          String javaTypeName = child.getStringAttribute("javaType");
          String jdbcTypeName = child.getStringAttribute("jdbcType");
          String handlerTypeName = child.getStringAttribute("handler");
          Class<?> javaTypeClass = resolveClass(javaTypeName);
          JdbcType jdbcType = resolveJdbcType(jdbcTypeName);
          Class<?> typeHandlerClass = resolveClass(handlerTypeName);
          // 注册 typeHandler
          if (javaTypeClass != null) {
            if (jdbcType == null) {
              typeHandlerRegistry.register(javaTypeClass, typeHandlerClass);
            } else {
              typeHandlerRegistry.register(javaTypeClass, jdbcType, typeHandlerClass);
            }
          } else {
            typeHandlerRegistry.register(typeHandlerClass);
          }
        }
      }
    }
  }

  private void mapperElement(XNode parent) throws Exception {
    if (parent != null) {
      // 遍历子节点
      for (XNode child : parent.getChildren()) {
        // 如果是 package 标签，则扫描该包
        if ("package".equals(child.getName())) {
          // 获得包名
          String mapperPackage = child.getStringAttribute("name");
          // 添加到 configuration 中
          configuration.addMappers(mapperPackage);
          // 如果是 mapper 标签，
        } else {
          String resource = child.getStringAttribute("resource");
          String url = child.getStringAttribute("url");
          String mapperClass = child.getStringAttribute("class");
          // 使用相对于类路径的资源引用
          if (resource != null && url == null && mapperClass == null) {
            ErrorContext.instance().resource(resource);
            // 获得 resource 的 InputStream 对象
            try(InputStream inputStream = Resources.getResourceAsStream(resource)) {
              // 创建 XMLMapperBuilder 对象
              XMLMapperBuilder mapperParser = new XMLMapperBuilder(inputStream, configuration, resource, configuration.getSqlFragments());
              // 执行解析
              mapperParser.parse();
            }
            // 使用完全限定资源定位符（URL）
          } else if (resource == null && url != null && mapperClass == null) {
            ErrorContext.instance().resource(url);
            // 获得 url 的 InputStream 对象
            try(InputStream inputStream = Resources.getUrlAsStream(url)){
              // 创建 XMLMapperBuilder 对象
              XMLMapperBuilder mapperParser = new XMLMapperBuilder(inputStream, configuration, url, configuration.getSqlFragments());
              // 执行解析
              mapperParser.parse();
            }
            // 使用映射器接口实现类的完全限定类名
          } else if (resource == null && url == null && mapperClass != null) {
            // 获得 Mapper 接口
            Class<?> mapperInterface = Resources.classForName(mapperClass);
            // 添加到 configuration 中
            configuration.addMapper(mapperInterface);
          } else {
            throw new BuilderException("A mapper element may only specify a url, resource or class, but not more than one.");
          }
        }
      }
    }
  }

  private boolean isSpecifiedEnvironment(String id) {
    if (environment == null) {
      throw new BuilderException("No environment specified.");
    }
    if (id == null) {
      throw new BuilderException("Environment requires an id attribute.");
    }
    return environment.equals(id);
  }

}
