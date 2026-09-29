package process;

import org.barco.platform.tenancy.RowSecurityConfiguration;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

/**
 * A hand-built bean as Spring would hand it out since MIG-258: behind platform-commons' AcrossTenantsPostProcessor, so
 * its {@code @AcrossTenants} methods run across workspaces. A test that runs a system path on
 * {@link ScratchPostgres#appPool()} -- under row security, with nobody signed in -- wraps the bean with this; without
 * it (or without the annotation) the path sees no workspace's rows, which is what the test is there to catch.
 */
public final class AcrossTenantsProxy {

    private AcrossTenantsProxy() {
    }

    @SuppressWarnings("unchecked")
    public static <T> T of(T bean) {
        RowSecurityConfiguration.AcrossTenantsPostProcessor processor = new RowSecurityConfiguration.AcrossTenantsPostProcessor();
        processor.setBeanFactory(new DefaultListableBeanFactory());
        return (T) processor.postProcessAfterInitialization(bean, bean.getClass().getSimpleName());
    }
}
