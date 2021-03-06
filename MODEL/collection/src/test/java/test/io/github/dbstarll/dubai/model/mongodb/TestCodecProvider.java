package test.io.github.dbstarll.dubai.model.mongodb;

import com.mongodb.ConnectionString;
import com.mongodb.MongoTimeoutException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.internal.MongoClientImpl;
import io.github.dbstarll.dubai.model.collection.Collection;
import io.github.dbstarll.dubai.model.collection.CollectionFactory;
import io.github.dbstarll.dubai.model.collection.test.*;
import io.github.dbstarll.dubai.model.collection.test.SimpleEntity.Type;
import io.github.dbstarll.dubai.model.entity.EntityFactory;
import io.github.dbstarll.dubai.model.entity.EntityModifier;
import io.github.dbstarll.dubai.model.mongodb.MongoClientFactory;
import io.github.dbstarll.utils.lang.EncryptUtils;
import io.github.dbstarll.utils.lang.bytes.Bytes;
import junit.framework.TestCase;
import org.apache.commons.io.IOUtils;
import org.bson.*;
import org.bson.codecs.Codec;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.EncoderContext;
import org.bson.codecs.configuration.CodecConfigurationException;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.types.ObjectId;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertNotEquals;

public class TestCodecProvider extends TestCase {
    private MongoClient client;
    private MongoDatabase database;
    private CollectionFactory collectionFactory;

    @Override
    protected void setUp() throws Exception {
        this.client = MongoClients.create(
                new MongoClientFactory(new Bytes(EncryptUtils.sha("y1cloud.com", 256))).getMongoClientSettingsbuilder()
                        .applyConnectionString(new ConnectionString("mongodb://localhost:12345/pumpkin"))
                        .applyToClusterSettings(s -> {
                            s.serverSelectionTimeout(100, TimeUnit.MILLISECONDS);
                        }).build()
        );
        this.database = client.getDatabase("test");
        this.collectionFactory = new CollectionFactory(database);
    }

    @Override
    protected void tearDown() throws Exception {
        this.collectionFactory = null;
        this.database = null;
        this.client.close();
        this.client = null;
    }

    /**
     * ?????????????????????Entity.
     */
    public void testInterfaceEntity() {
        final Collection<SimpleEntity> collection = collectionFactory.newInstance(SimpleEntity.class);
        final SimpleEntity entity = EntityFactory.newInstance(SimpleEntity.class);
        entity.setType(Type.t1);
        entity.setBytes(new ObjectId().toByteArray());

        try {
            collection.save(entity);
            fail("throw MongoTimeoutException");
        } catch (Throwable ex) {
            assertEquals(MongoTimeoutException.class, ex.getClass());
        }
    }

    /**
     * ????????????Class???Entity.
     */
    public void testClassEntity() {
        final Collection<SimpleClassEntity> collection = collectionFactory.newInstance(SimpleClassEntity.class);
        final SimpleClassEntity entity = EntityFactory.newInstance(SimpleClassEntity.class);

        try {
            collection.save(entity);
            fail("throw MongoTimeoutException");
        } catch (Throwable ex) {
            assertEquals(MongoTimeoutException.class, ex.getClass());
        }
    }

    /**
     * ???????????????PojoFields???Proxy.
     */
    public void testProxyNoPojoFields() {
        final Collection<SimpleEntity> collection = collectionFactory.newInstance(SimpleEntity.class);
        final SimpleEntity entity = (SimpleEntity) Proxy.newProxyInstance(SimpleEntity.class.getClassLoader(),
                new Class[]{SimpleEntity.class, EntityModifier.class}, new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        return null;
                    }
                });

        try {
            collection.save(entity);
            fail("throw MongoTimeoutException");
        } catch (Throwable ex) {
            assertEquals(MongoTimeoutException.class, ex.getClass());
        }
    }

    /**
     * ?????????????????????Entity?????????.
     */
    public void testNoEntity() {
        final MongoCollection<NotEntity> collection = database.getCollection("oid", NotEntity.class);

        try {
            collection.insertOne(new NotEntity());
            fail("throw CodecConfigurationException");
        } catch (Throwable ex) {
            assertCodecNotFound(ex);
        }
    }

    /**
     * ??????EntityCodec.
     */
    public void testEntityCodec() {
        final CodecRegistry registry = ((MongoClientImpl) client).getCodecRegistry();
        final SimpleEntity entity = EntityFactory.newInstance(SimpleEntity.class);
        @SuppressWarnings("unchecked") final Codec<SimpleEntity> codec = (Codec<SimpleEntity>) registry
                .get(entity.getClass());
        assertEquals(codec.getClass().getName(), "io.github.dbstarll.dubai.model.mongodb.MongoClientFactory$EntityCodec");
        assertNotEquals(SimpleEntity.class, codec.getEncoderClass());
        assertEquals(entity.getClass(), codec.getEncoderClass());

        entity.setType(Type.t1);
        final BsonDocument document = new BsonDocument();
        codec.encode(new BsonDocumentWriter(document), entity, EncoderContext.builder().build());
        assertEquals("t1", document.getString("type").getValue());

        try {
            codec.decode(new BsonDocumentReader(document), DecoderContext.builder().build());
            fail("throw UnsupportedOperationException");
        } catch (Throwable ex) {
            assertEquals(UnsupportedOperationException.class, ex.getClass());
        }
    }

    /**
     * ??????EnumCodec.
     */
    public void testEnumCodec() {
        final CodecRegistry registry = ((MongoClientImpl) client).getCodecRegistry();
        final Codec<SimpleEntity.Type> codec = registry.get(SimpleEntity.Type.class);
        assertEquals(codec.getClass().getName(), "io.github.dbstarll.dubai.model.mongodb.MongoClientFactory$EnumCodec");
        assertEquals(SimpleEntity.Type.class, codec.getEncoderClass());

        final BsonDocument document = new BsonDocument();
        final BsonWriter writer = new BsonDocumentWriter(document);
        writer.writeStartDocument();
        writer.writeName("type");
        codec.encode(writer, SimpleEntity.Type.t1, EncoderContext.builder().build());
        assertEquals("t1", document.getString("type").getValue());

        document.append("type2", new BsonString("ttt"));
        final BsonReader reader = new BsonDocumentReader(document);
        reader.readStartDocument();
        reader.readName();
        assertEquals(SimpleEntity.Type.t1, codec.decode(reader, DecoderContext.builder().build()));
        reader.readName();
        assertNull(codec.decode(reader, DecoderContext.builder().build()));
    }

    /**
     * ??????ImageCodec.
     */
    public void testImageCodecEncode() throws IOException {
        final CodecRegistry registry = ((MongoClientImpl) client).getCodecRegistry();
        final Codec<byte[]> codec = registry.get(byte[].class);
        assertEquals(codec.getClass().getName(), "io.github.dbstarll.dubai.model.mongodb.MongoClientFactory$ImageCodec");
        assertEquals(byte[].class, codec.getEncoderClass());

        testImageCodec(codec, "png.png", true, true);
        testImageCodec(codec, "jpg.jpg", true, true);
        testImageCodec(codec, "ico.ico", false, true);
        testImageCodec(codec, "txt.txt", false, true);
    }

    /**
     * ??????ImageCodec.
     */
    public void testImageCodecNotEncode() throws IOException {
        this.client = MongoClients.create(
                new MongoClientFactory().getMongoClientSettingsbuilder()
                        .applyConnectionString(new ConnectionString("mongodb://localhost:12345/pumpkin"))
                        .applyToClusterSettings(s -> {
                            s.serverSelectionTimeout(100, TimeUnit.MILLISECONDS);
                        }).build()
        );
        final CodecRegistry registry = ((MongoClientImpl) client).getCodecRegistry();
        final Codec<byte[]> codec = registry.get(byte[].class);
        assertEquals(codec.getClass().getName(), "io.github.dbstarll.dubai.model.mongodb.MongoClientFactory$ImageCodec");
        assertEquals(byte[].class, codec.getEncoderClass());

        testImageCodec(codec, "png.png", true, false);
        testImageCodec(codec, "jpg.jpg", true, false);
        testImageCodec(codec, "ico.ico", false, false);
        testImageCodec(codec, "txt.txt", false, false);
    }

    private void testImageCodec(final Codec<byte[]> codec, String resource, boolean image, boolean encode)
            throws IOException {
        final BsonDocument document = new BsonDocument();
        final BsonWriter writer = new BsonDocumentWriter(document);
        final byte[] data = read(resource);
        writer.writeStartDocument();
        writer.writeName("data");
        codec.encode(writer, data, EncoderContext.builder().build());
        final byte[] save = document.getBinary("data").getData();
        assertEquals(data.length, save.length);
        assertEquals(!encode, Arrays.equals(data, save));

        document.append("original", new BsonBinary(data));

        final BsonReader reader = new BsonDocumentReader(document);
        reader.readStartDocument();
        reader.readName();
        final byte[] load = codec.decode(reader, DecoderContext.builder().build());
        assertEquals(!encode, Arrays.equals(load, save));
        assertTrue(Arrays.equals(load, data));

        reader.readName();
        final byte[] original = codec.decode(reader, DecoderContext.builder().build());
        if (image) {
            assertEquals(!encode, Arrays.equals(original, save));
            assertTrue(Arrays.equals(original, data));
        } else {
            assertTrue(Arrays.equals(original, save));
            assertEquals(!encode, Arrays.equals(original, data));
        }
    }

    private byte[] read(String resource) throws IOException {
        final InputStream in = ClassLoader.getSystemResourceAsStream(resource);
        try {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            try {
                IOUtils.copy(in, out);
            } finally {
                out.close();
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    /**
     * ???????????????????????????Entity.
     */
    public void testGenericEntity() {
        final Collection<SimpleGenericEntity> collection = collectionFactory.newInstance(SimpleGenericEntity.class);
        final SimpleGenericEntity entity = EntityFactory.newInstance(SimpleGenericEntity.class);
        entity.setKey("key");
        entity.setValue(100);

        try {
            collection.save(entity);
            fail("throw MongoTimeoutException");
        } catch (Throwable ex) {
            assertEquals(MongoTimeoutException.class, ex.getClass());
        }
    }

    /**
     * ?????????????????????????????????Entity.
     */
    public void testMultiSetterEntity() {
        final Collection<MultiSetterEntity> collection = collectionFactory.newInstance(MultiSetterEntity.class);
        final MultiSetterEntity entity = EntityFactory.newInstance(MultiSetterEntity.class);
        entity.setData(true);
        entity.setData(100);

        try {
            collection.save(entity);
            fail("throw CodecConfigurationException");
        } catch (Throwable ex) {
            assertCodecNotFound(ex);
        }
    }

    /**
     * ???????????????getter???setter??????????????????Entity.
     */
    public void testDiffGetterSetterEntity() {
        final Collection<DiffGetterSetterEntity> collection = collectionFactory.newInstance(DiffGetterSetterEntity.class);
        final DiffGetterSetterEntity entity = EntityFactory.newInstance(DiffGetterSetterEntity.class);

        try {
            collection.save(entity);
            fail("throw CodecConfigurationException");
        } catch (Throwable ex) {
            assertCodecNotFound(ex);
        }
    }

    /**
     * ????????????setter???Entity.
     */
    public void testOnlySetterEntity() {
        final Collection<OnlySetterEntity> collection = collectionFactory.newInstance(OnlySetterEntity.class);
        final OnlySetterEntity entity = EntityFactory.newInstance(OnlySetterEntity.class);

        try {
            collection.save(entity);
            fail("throw CodecConfigurationException");
        } catch (Throwable ex) {
            assertCodecNotFound(ex);
        }
    }

    /**
     * ????????????getter???Entity.
     */
    public void testOnlyGetterEntity() {
        final Collection<OnlyGetterEntity> collection = collectionFactory.newInstance(OnlyGetterEntity.class);
        final OnlyGetterEntity entity = EntityFactory.newInstance(OnlyGetterEntity.class);

        try {
            collection.save(entity);
            fail("throw CodecConfigurationException");
        } catch (Throwable ex) {
            assertCodecNotFound(ex);
        }
    }

    /**
     * ???????????????????????????????????????Entity.
     */
    public void testDirectMethodGenericEntity() {
        final Collection<DirectMethodGenericEntity> collection = collectionFactory
                .newInstance(DirectMethodGenericEntity.class);
        final DirectMethodGenericEntity entity = EntityFactory.newInstance(DirectMethodGenericEntity.class);

        try {
            collection.save(entity);
            fail("throw CodecConfigurationException");
        } catch (Throwable ex) {
            assertCodecNotFound(ex);
        }
    }

    /**
     * ???????????????????????????????????????Entity.
     */
    public void testInheritMethodGenericEntity() {
        final Collection<InheritMethodGenericEntity> collection = collectionFactory
                .newInstance(InheritMethodGenericEntity.class);
        final InheritMethodGenericEntity entity = EntityFactory.newInstance(InheritMethodGenericEntity.class);

        try {
            collection.save(entity);
            fail("throw CodecConfigurationException");
        } catch (Throwable ex) {
            assertCodecNotFound(ex);
        }
    }

    /**
     * ???????????????????????????????????????Entity.
     */
    public void testInheritMethodGenericHidingEntity() {
        final Collection<InheritMethodGenericHidingEntity> collection = collectionFactory
                .newInstance(InheritMethodGenericHidingEntity.class);
        final InheritMethodGenericHidingEntity entity = EntityFactory.newInstance(InheritMethodGenericHidingEntity.class);

        try {
            collection.save(entity);
            fail("throw CodecConfigurationException");
        } catch (Throwable ex) {
            assertCodecNotFound(ex);
        }
    }

    private void assertCodecNotFound(Throwable ex) {
        assertEquals(CodecConfigurationException.class, ex.getClass());
        assertTrue(ex.getMessage().startsWith("Can't find a codec for"));
        assertNull(ex.getCause());
    }
}
