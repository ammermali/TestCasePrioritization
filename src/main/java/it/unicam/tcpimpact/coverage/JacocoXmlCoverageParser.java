package it.unicam.tcpimpact.coverage;
import it.unicam.tcpimpact.graph.model.ImpactEdge;
import it.unicam.tcpimpact.graph.model.MethodNode;
import it.unicam.tcpimpact.graph.model.MethodNodeType;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class JacocoXmlCoverageParser {

    public Set<ImpactEdge> parse(Path reportPath, Collection<MethodNode> graphNodes) throws IOException {
        Document document = parseXml(reportPath);
        Element report = document.getDocumentElement();
        Optional<MethodNode> testMethod = findTestMethod(report.getAttribute("name"), graphNodes);
        if(testMethod.isEmpty()){
            return Set.of();
        }

        Map<String, CoveredSourceFile> coveredSourceFiles = coveredSourceFiles(document);
        Set<ImpactEdge> edges = new LinkedHashSet<>();
        for(MethodNode productionMethod : graphNodes){
            if(!isProductionMethod(productionMethod)){
                continue;
            }
            CoveredSourceFile sourceFile = findCoveredSourceFile(coveredSourceFiles, productionMethod.filePath()).orElse(null);
            if(sourceFile == null){
                continue;
            }
            int executableLines = sourceFile.executableLinesInRange(productionMethod.startLine(), productionMethod.endLine());
            int coveredLines = sourceFile.coveredLinesInRange(productionMethod.startLine(), productionMethod.endLine());
            if(coveredLines == 0){
                continue;
            }
            double coverageRatio = executableLines == 0 ? 1.0 : (double) coveredLines / executableLines;
            edges.add(ImpactEdge.testImpact(
                    productionMethod.id(),
                    testMethod.get().id(),
                    coverageRatio,
                    coveredLines
            ));
        }
        return edges;
    }

    private Document parseXml(Path reportPath) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);

            var builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
            Document document = builder.parse(reportPath.toFile());
            document.getDocumentElement().normalize();
            return document;
        } catch (Exception exception) {
            throw new IOException("Unable to parse JaCoCo XML report: " + reportPath, exception);
        }
    }

    private Optional<MethodNode> findTestMethod(String reportName, Collection<MethodNode> graphNodes) {
        if(reportName == null || reportName.isBlank()){
            return Optional.empty();
        }
        for(MethodNode node : graphNodes){
            if(node.nodeType() != MethodNodeType.TEST_METHOD){
                continue;
            }
            String qualifiedTestName = node.packageName().isBlank()
                    ? node.className() + "." + node.methodName()
                    : node.packageName() + "." + node.className() + "." + node.methodName();
            if(reportName.equals(qualifiedTestName)){
                return Optional.of(node);
            }
        }
        return Optional.empty();
    }

    private Map<String, CoveredSourceFile> coveredSourceFiles(Document document) {
        Map<String, CoveredSourceFile> result = new LinkedHashMap<>();
        NodeList packages = document.getElementsByTagName("package");
        for(int packageIndex = 0; packageIndex < packages.getLength(); packageIndex++){
            Element packageElement = (Element) packages.item(packageIndex);
            String packagePath = packageElement.getAttribute("name");
            NodeList sourceFiles = packageElement.getElementsByTagName("sourcefile");
            for(int sourceFileIndex = 0; sourceFileIndex < sourceFiles.getLength(); sourceFileIndex++){
                Element sourceFileElement = (Element) sourceFiles.item(sourceFileIndex);
                String sourcePath = sourcePath(packagePath, sourceFileElement.getAttribute("name"));
                CoveredSourceFile sourceFile = new CoveredSourceFile();
                NodeList lines = sourceFileElement.getElementsByTagName("line");
                for(int lineIndex = 0; lineIndex < lines.getLength(); lineIndex++){
                    Element lineElement = (Element) lines.item(lineIndex);
                    int lineNumber = intAttribute(lineElement, "nr");
                    int missedInstructions = intAttribute(lineElement, "mi");
                    int coveredUnits = intAttribute(lineElement, "ci");
                    int missedBranches = intAttribute(lineElement, "mb");
                    int coveredBranches = intAttribute(lineElement, "cb");
                    boolean executable = missedInstructions + coveredUnits + missedBranches + coveredBranches > 0;
                    boolean covered = coveredUnits > 0 || coveredBranches > 0;
                    sourceFile.add(lineNumber, executable, covered);
                }
                result.put(sourcePath, sourceFile);
            }
        }
        return result;
    }

    private Optional<CoveredSourceFile> findCoveredSourceFile(Map<String, CoveredSourceFile> coveredSourceFiles, String productionPath) {
        String normalizedProductionPath = normalizePath(productionPath);
        CoveredSourceFile exact = coveredSourceFiles.get(normalizedProductionPath);
        if(exact != null){
            return Optional.of(exact);
        }
        return coveredSourceFiles.entrySet()
                .stream()
                .filter(entry -> normalizedProductionPath.endsWith(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst();
    }

    private String sourcePath(String packagePath, String sourceFileName) {
        String normalizedPackage = packagePath == null ? "" : packagePath.replace("/", "\\");
        if(normalizedPackage.isBlank()){
            return normalizePath(sourceFileName);
        }
        return normalizePath(normalizedPackage + "\\" + sourceFileName);
    }

    private int intAttribute(Element element, String attributeName) {
        String value = element.getAttribute(attributeName);
        if(value == null || value.isBlank()){
            return 0;
        }
        return Integer.parseInt(value);
    }

    private boolean isProductionMethod(MethodNode node) {
        return node.nodeType() == MethodNodeType.METHOD
                || node.nodeType() == MethodNodeType.CONSTRUCTOR_METHOD
                || node.nodeType() == MethodNodeType.ABSTRACT_METHOD
                || node.nodeType() == MethodNodeType.INTERFACE_METHOD
                || node.nodeType() == MethodNodeType.DEFAULT_INTERFACE_METHOD;
    }

    private String normalizePath(String path) {
        return path.replace("/", "\\");
    }

    private static class CoveredSourceFile {
        private final Map<Integer, CoveredLine> lines = new LinkedHashMap<>();

        void add(int lineNumber, boolean executable, boolean covered) {
            lines.put(lineNumber, new CoveredLine(executable, covered));
        }

        int executableLinesInRange(int startLine, int endLine) {
            return (int) lines.entrySet()
                    .stream()
                    .filter(entry -> entry.getKey() >= startLine && entry.getKey() <= endLine)
                    .filter(entry -> entry.getValue().executable())
                    .count();
        }

        int coveredLinesInRange(int startLine, int endLine) {
            return (int) lines.entrySet()
                    .stream()
                    .filter(entry -> entry.getKey() >= startLine && entry.getKey() <= endLine)
                    .filter(entry -> entry.getValue().covered())
                    .count();
        }

    }

    private record CoveredLine(boolean executable, boolean covered) { }
}
