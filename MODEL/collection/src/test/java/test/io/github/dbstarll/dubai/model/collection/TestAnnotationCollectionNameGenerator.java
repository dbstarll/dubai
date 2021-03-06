package test.io.github.dbstarll.dubai.model.collection;

import io.github.dbstarll.dubai.model.collection.AnnotationCollectionNameGenerator;
import io.github.dbstarll.dubai.model.collection.CollectionInitializeException;
import io.github.dbstarll.dubai.model.collection.CollectionNameGenerator;
import io.github.dbstarll.dubai.model.entity.Entity;
import io.github.dbstarll.dubai.model.entity.Namespace;
import io.github.dbstarll.dubai.model.entity.Table;
import junit.framework.TestCase;
import org.bson.types.ObjectId;

import java.util.Date;

public class TestAnnotationCollectionNameGenerator extends TestCase {
    private CollectionNameGenerator collectionNameGenerator;

    @Override
    protected void setUp() throws Exception {
        this.collectionNameGenerator = new AnnotationCollectionNameGenerator();
    }

    @Override
    protected void tearDown() throws Exception {
        this.collectionNameGenerator = null;
    }

    public void testSingleWord() {
        assertEquals("one", collectionNameGenerator.generateCollectionName(One.class));
    }

    public void testTwoWord() {
        assertEquals("two_two", collectionNameGenerator.generateCollectionName(TwoTwo.class));
    }

    public void testInheritedSchema() {
        assertEquals("t1_abc", collectionNameGenerator.generateCollectionName(Three.class));
    }

    public void testDirectSchema() {
        assertEquals("t2_four", collectionNameGenerator.generateCollectionName(Four.class));
    }

    public void testClassSchema() {
        assertEquals("t3_five", collectionNameGenerator.generateCollectionName(Five.class));
    }

    public void testEmptySchema() {
        assertEquals("six", collectionNameGenerator.generateCollectionName(Six.class));
    }

    /**
     * 测试抛出异常.
     */
    public void testNoTable() {
        try {
            collectionNameGenerator.generateCollectionName(NoTable.class);
        } catch (CollectionInitializeException ex) {
            assertTrue(ex.getMessage().startsWith("Table annotation not find on entity class: "));
        }
    }

    @Table
    public static interface One extends Entity {
    }

    @Table
    public static interface TwoTwo extends Entity {
    }

    @Namespace("t1")
    public static interface NoTable extends Entity {
    }

    @Table("abc")
    public static interface Three extends NoTable {
    }

    @Table
    @Namespace("t2_")
    public static interface Four extends NoTable {
    }

    @Namespace("t3_")
    public static class ClassEntity implements Entity {
        private static final long serialVersionUID = -2156207737656566869L;

        @Override
        public ObjectId getId() {
            return null;
        }

        @Override
        public Date getDateCreated() {
            return null;
        }

        @Override
        public Date getLastModified() {
            return null;
        }

        @Override
        public Object clone() throws CloneNotSupportedException {
            return super.clone();
        }
    }

    @Table
    public static class Five extends ClassEntity {
        private static final long serialVersionUID = 5920039216901835804L;
    }

    @Table
    @Namespace
    public static interface Six extends Entity {
    }
}
