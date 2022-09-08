package org.gephi.plugins;

import java.util.Arrays;
import java.util.Collection;
import java.util.Optional;
import java.util.stream.Collectors;
import org.gephi.graph.GraphGenerator;
import org.gephi.graph.api.Column;
import org.gephi.graph.api.GraphModel;
import org.gephi.layout.LayoutModelImpl;
import org.gephi.layout.LayoutModelPersistenceProvider;
import org.gephi.layout.spi.Layout;
import org.gephi.layout.spi.LayoutBuilder;
import org.gephi.layout.spi.LayoutProperty;
import org.gephi.layout.spi.LayoutUI;
import org.gephi.project.io.utils.GephiFormat;
import org.gephi.workspace.impl.WorkspaceImpl;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.openide.util.Lookup;

@RunWith(Parameterized.class)
public class LayoutIT {
    private final LayoutBuilder layoutBuilder;
    private Layout layout;
    private GraphModel graphModel;

    public LayoutIT(LayoutBuilder layout) {
        this.layoutBuilder = layout;
    }

    @Parameterized.Parameters(name = "{0}")
    public static Collection<? extends LayoutBuilder> configs() {
        return Lookup.getDefault().lookupAll(LayoutBuilder.class);
    }

    @Before
    public void setUp() {
        graphModel = GraphGenerator.build().generateSmallRandomGraph().addIntNodeColumn().getGraph().getModel();
        layout = layoutBuilder.buildLayout();
        layout.setGraphModel(graphModel);
    }

    @After
    public void cleanUp() {
        graphModel = null;
        layout.setGraphModel(null);
        layout = null;
    }

    @Test
    public void testLayoutBuilder() {
        Assert.assertNotNull("Layout builder can't be null", layoutBuilder);
        Assert.assertTrue("Layout builder name can't be null or empty",
            layoutBuilder.getName() != null & !layoutBuilder.getName().isEmpty());
    }

    @Test
    public void testLayoutUI() {
        Assert.assertNotNull("Layout builder needs to have a UI", layoutBuilder.getUI());
        LayoutUI layoutUI = layoutBuilder.getUI();
        Assert.assertNotNull("Layout UI needs to have a description", layoutUI.getDescription());

        int speed = layoutUI.getSpeedRank();
        int quality = layoutUI.getQualityRank();
        Assert.assertTrue("Speed appraisal must be either -1 or between 1 and 5",
            speed == -1 || (speed >= 1 && speed <= 5));
        Assert.assertTrue("Quality appraisal must be either -1 or between 1 and 5",
            quality == -1 || (quality >= 1 && quality <= 5));
    }

    @Test
    public void testLayoutProperties() {
        for (LayoutProperty property : layout.getProperties()) {
            Assert.assertNotNull("Property canonical name can't be null", property.getCanonicalName());
            Assert.assertTrue(
                "Property " + property.getCanonicalName() + " can't read, make sure the getters are set correctly",
                property.getProperty().canRead());
            Assert.assertTrue(
                "Property " + property.getCanonicalName() + " can't write, make sure the setters are set correctly",
                property.getProperty().canWrite());
        }

        Assert.assertEquals("Duplicate property canonical names, make sure they are unique",
            layout.getProperties().length, Arrays.stream(layout.getProperties()).map(
                LayoutProperty::getCanonicalName).collect(
                Collectors.toSet()).size());
    }

    @Test
    public void testResetProperties() {
        layout.resetPropertiesValues();
    }

    @Test
    public void testSerialization() throws Exception {
        LayoutModelImpl layoutModel = newLayoutModel();
        layout.resetPropertiesValues();
        layoutModel.saveProperties(layout);
        GephiFormat.testXMLPersistenceProvider(new LayoutModelPersistenceProvider(), layoutModel.getWorkspace());
    }

    @Test
    public void testSerialisationWithColumn() throws Exception {
        layout.resetPropertiesValues();
        Optional<LayoutProperty>
            columnProperty = Arrays.stream(layout.getProperties()).filter(lp -> lp.getProperty().getValueType().equals(Column.class)).findFirst();
        LayoutModelImpl layoutModel = newLayoutModel();
        if (columnProperty.isPresent()) {
            columnProperty.get().getProperty().setValue(graphModel.getNodeTable().getColumn(GraphGenerator.INT_COLUMN));
            layoutModel.saveProperties(layout);
            GephiFormat.testXMLPersistenceProvider(new LayoutModelPersistenceProvider(), layoutModel.getWorkspace());
        }
    }

    // Utilities

    public static LayoutModelImpl newLayoutModel() {
        WorkspaceImpl workspace = new WorkspaceImpl(null, 0);
        LayoutModelImpl model = new LayoutModelImpl(workspace);
        workspace.add(model);
        return model;
    }
}
