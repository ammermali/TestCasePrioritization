package it.unicam.tcpimpact.coverage.optimized;

import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IBundleCoverage;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.SessionInfoStore;
import org.jacoco.core.tools.ExecFileLoader;
import org.jacoco.report.IReportVisitor;
import org.jacoco.report.xml.XMLFormatter;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;


public class JacocoReportGenerator {

    /**
     * @param execFile the .exec file produced for one test method
     * @param compiledClassesDir directory containing the compiled .class files to analyze
     * @param xmlOutputFile where to write the generated XML report
     * @param reportName name shown as the top-level bundle name in the report
     */
    public void generateXmlReport(Path execFile, Path compiledClassesDir, Path xmlOutputFile, String reportName)
            throws IOException {

        ExecFileLoader execFileLoader = new ExecFileLoader();
        execFileLoader.load(execFile.toFile());

        ExecutionDataStore executionDataStore = execFileLoader.getExecutionDataStore();
        SessionInfoStore sessionInfoStore = execFileLoader.getSessionInfoStore();

        CoverageBuilder coverageBuilder = new CoverageBuilder();
        Analyzer analyzer = new Analyzer(executionDataStore, coverageBuilder);
        analyzer.analyzeAll(compiledClassesDir.toFile());

        IBundleCoverage bundleCoverage = coverageBuilder.getBundle(reportName);

        createParentDirectories(xmlOutputFile);

        try (FileOutputStream outputStream = new FileOutputStream(xmlOutputFile.toFile())) {
            XMLFormatter xmlFormatter = new XMLFormatter();
            IReportVisitor reportVisitor = xmlFormatter.createVisitor(outputStream);

            reportVisitor.visitInfo(sessionInfoStore.getInfos(), executionDataStore.getContents());
            reportVisitor.visitBundle(bundleCoverage, null);
            reportVisitor.visitEnd();
        }
    }

    private void createParentDirectories(Path file) throws IOException {
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
    }
}