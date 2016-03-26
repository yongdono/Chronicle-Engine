/*
 *
 *  *     Copyright (C) 2016  higherfrequencytrading.com
 *  *
 *  *     This program is free software: you can redistribute it and/or modify
 *  *     it under the terms of the GNU Lesser General Public License as published by
 *  *     the Free Software Foundation, either version 3 of the License.
 *  *
 *  *     This program is distributed in the hope that it will be useful,
 *  *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  *     GNU Lesser General Public License for more details.
 *  *
 *  *     You should have received a copy of the GNU Lesser General Public License
 *  *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package net.openhft.chronicle.engine.queue;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.OS;
import net.openhft.chronicle.core.pool.ClassAliasPool;
import net.openhft.chronicle.core.time.SetTimeProvider;
import net.openhft.chronicle.engine.ChronicleMapKeyValueStoreTest;
import net.openhft.chronicle.engine.VanillaAssetTreeEgMain;
import net.openhft.chronicle.engine.api.tree.Asset;
import net.openhft.chronicle.engine.api.tree.AssetTree;
import net.openhft.chronicle.engine.api.tree.RequestContext;
import net.openhft.chronicle.engine.fs.ChronicleMapGroupFS;
import net.openhft.chronicle.engine.fs.FilePerKeyGroupFS;
import net.openhft.chronicle.engine.server.ServerEndpoint;
import net.openhft.chronicle.engine.tree.ChronicleQueueView;
import net.openhft.chronicle.engine.tree.QueueView;
import net.openhft.chronicle.engine.tree.VanillaAsset;
import net.openhft.chronicle.engine.tree.VanillaAssetTree;
import net.openhft.chronicle.network.TCPRegistry;
import net.openhft.chronicle.network.connection.TcpChannelHub;
import net.openhft.chronicle.queue.ChronicleQueue;
import net.openhft.chronicle.queue.ExcerptAppender;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;
import net.openhft.chronicle.wire.WireKey;
import net.openhft.chronicle.wire.WireType;
import net.openhft.chronicle.wire.YamlLogging;
import org.jetbrains.annotations.NotNull;
import org.junit.*;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static net.openhft.chronicle.engine.Utils.methodName;
import static net.openhft.chronicle.engine.queue.SimpleQueueViewTest.deleteFile;
import static org.junit.Assert.assertEquals;

/**
 * Created by Rob Austin
 */

@RunWith(value = Parameterized.class)
public class QueueReplicationSourceSycnRollTest {

    public static final WireType WIRE_TYPE = WireType.TEXT;
    public static final String NAME = "/ChMaps/test";
    public static ServerEndpoint serverEndpoint1;
    public static ServerEndpoint serverEndpoint2;
    public static ServerEndpoint serverEndpoint3;
    private static AssetTree tree1;
    private static AssetTree tree2;
    private static AtomicReference<Throwable> t = new AtomicReference();
    private final WireType wireType;
    @Rule
    public TestName name = new TestName();
    String methodName;
    private ChronicleQueue q1;
    private ChronicleQueue q2;

    public QueueReplicationSourceSycnRollTest(WireType wireType) {
        this.wireType = wireType;
    }

    @Parameterized.Parameters
    public static Collection<Object[]> data() throws IOException {
        final int initialCapacity = 1;
        final List<Object[]> list = new ArrayList<>(initialCapacity);

        for (int i = 0; i < initialCapacity; i++) {
            list.add(new Object[]{WireType.BINARY});
        }

        return list;
    }

    @NotNull
    private static AssetTree create(final int hostId, WireType writeType, final String clusterTwo) {
        AssetTree tree = new VanillaAssetTree((byte) hostId)
                .forTesting(x -> t.compareAndSet(null, x))
                .withConfig(resourcesDir() + "/cmkvst", OS.TARGET + "/" + hostId);
        final Asset queue = tree.root().acquireAsset("queue");
        queue.addLeafRule(QueueView.class, VanillaAsset.LAST + "chronicle queue", (context, asset) ->
                new ChronicleQueueView(context.wireType(writeType).cluster(clusterTwo), asset));


        VanillaAssetTreeEgMain.registerTextViewofTree("host " + hostId, tree);

        return tree;
    }

    @NotNull
    public static String resourcesDir() {
        String path = ChronicleMapKeyValueStoreTest.class.getProtectionDomain().getCodeSource().getLocation().getPath();
        if (path == null)
            return ".";
        return new File(path).getParentFile().getParentFile() + "/src/test/resources";
    }

    @Before
    public void before() throws IOException, InterruptedException {
        YamlLogging.setAll(false);

        methodName(name.getMethodName());
        methodName = name.getMethodName().substring(0, name.getMethodName().indexOf('['));

        //YamlLogging.showServerWrites = true;

        ClassAliasPool.CLASS_ALIASES.addAlias(ChronicleMapGroupFS.class);
        ClassAliasPool.CLASS_ALIASES.addAlias(FilePerKeyGroupFS.class);
        //Delete any files from the last run
        Files.deleteIfExists(Paths.get(OS.TARGET, NAME));

        TCPRegistry.createServerSocketChannelFor(
                "clusterThree.host.port1",
                "clusterThree.host.port2",
                "clusterThree.host.port3",
                "host.port1",
                "host.port2",
                "host.port3");

        tree1 = create(1, wireType, "clusterThree");
        tree2 = create(2, wireType, "clusterThree");

        serverEndpoint1 = new ServerEndpoint("clusterThree.host.port1", tree1);
        serverEndpoint2 = new ServerEndpoint("clusterThree.host.port2", tree2);

    }

    @After
    public void after() throws IOException, InterruptedException {


        if (serverEndpoint1 != null)
            serverEndpoint1.close();
        if (serverEndpoint2 != null)
            serverEndpoint2.close();


        for (AssetTree tree : new AssetTree[]{tree1, tree2}) {
            if (tree == null)
                continue;

            try {
                final ChronicleQueueView queueView = (ChronicleQueueView) tree.acquireAsset("/queue/" + methodName).acquireView(QueueView.class);
                final File path = queueView.chronicleQueue().path();
                System.out.println("path=" + path);
                deleteFile(path);
            } catch (Exception ignore) {

            }
            tree.close();
        }


        TcpChannelHub.closeAllHubs();
        TCPRegistry.reset();

    }

    @After
    public void afterMethod() {
        final Throwable th = t.getAndSet(null);
        if (th != null) throw Jvm.rethrow(th);
    }

    @Ignore("JIRA- https://higherfrequencytrading.atlassian.net/browse/CE-200")
    @Test
    public void testAppendAndReadWithRollingB() throws IOException, InterruptedException {
        YamlLogging.setAll(true);
        SetTimeProvider stp = new SetTimeProvider();
        stp.currentTimeMillis(System.currentTimeMillis() - 3 * 86400_000L);

        final ChronicleQueue queue1 = new SingleChronicleQueueBuilder(Files.createTempDirectory
                ("chronicle" + "-").toFile())
                .wireType(this.wireType)
                .timeProvider(stp)
                .indexSpacing(1).indexCount(8)
                .build();


        final ChronicleQueue queue2 = new SingleChronicleQueueBuilder(Files.createTempDirectory
                ("chronicle" + "-").toFile())
                .wireType(this.wireType)
                .indexSpacing(1).indexCount(8)
                .build();


        final RequestContext context = new RequestContext("/queue", methodName);
        context.cluster("clusterThree");

        context.wireType(WireType.BINARY);
        context.type2(String.class);

        {
            final Asset asset = tree1.acquireAsset("/queue/" + methodName);
            asset.addView(QueueView.class, new ChronicleQueueView(queue1, context, asset));
        }

        {
            final Asset asset = tree2.acquireAsset("/queue/" + methodName);
            asset.addView(QueueView.class, new ChronicleQueueView(queue2, context,
                    asset));
        }


        final ExcerptAppender appender = queue1.createAppender();
        appender.writeDocument(w -> w.write(TestKey.test).int32(0));
        appender.writeDocument(w -> w.write(TestKey.test2).int32(1000));
        int cycle = appender.cycle();
        for (int i = 1; i <= 5; i++) {
            stp.currentTimeMillis(stp.currentTimeMillis() + 86400_000L);
            final int n = i;
            appender.writeDocument(w -> w.write(TestKey.test).int32(n));
            assertEquals(cycle + i, appender.cycle());
            appender.writeDocument(w -> w.write(TestKey.test2).int32(n + 1000));
            assertEquals(cycle + i, appender.cycle());
        }

        for (int i = 0; i < 10; i++) {
            if (queue1.dump().equals(queue2.dump()))
                break;
            Thread.sleep(100);
        }

        assertEquals(queue1.dump(), queue2.dump());
    }


    public enum TestKey implements WireKey {
        test, test2
    }
}

