package io.queueless.server.integration;

import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.webresources.*;
import java.nio.file.*;

/** Separate JVM used only by the opt-in multi-process MySQL/HTTP test. */
public final class ServerProcess {
    public static void main(String[] args) throws Exception {
        String url=System.getenv("QUEUELESS_TEST_DB_URL");
        if(url==null || !url.matches("jdbc:mysql://[^/]+/[a-zA-Z0-9_]*_test(?:\\?.*)?"))throw new IllegalStateException("Dedicated test database required");
        System.setProperty("queueless.db.url",url);
        Tomcat server=new Tomcat();
        server.setBaseDir(Files.createTempDirectory("queueless-process-test-").toString());
        server.setPort(0);server.getConnector().setProperty("address","127.0.0.1");
        var context=server.addWebapp("/queueless",Path.of("src/main/webapp").toAbsolutePath().toString());
        context.setParentClassLoader(ServerProcess.class.getClassLoader());
        var resources=new StandardRoot(context);
        resources.addPreResources(new DirResourceSet(resources,"/WEB-INF/classes",Path.of("target/classes").toAbsolutePath().toString(),"/"));
        context.setResources(resources);
        Runtime.getRuntime().addShutdownHook(new Thread(()->{try{server.stop();server.destroy();}catch(Exception ignored){}}));
        server.start();
        if(!context.getState().isAvailable())throw new IllegalStateException("Test backend startup failed");
        System.out.println("QUEUELESS_READY:"+server.getConnector().getLocalPort());
        System.out.flush();server.getServer().await();
    }
}
