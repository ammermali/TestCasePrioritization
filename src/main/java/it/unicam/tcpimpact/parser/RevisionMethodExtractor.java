package it.unicam.tcpimpact.parser;
import it.unicam.tcpimpact.git.FileReader;
import it.unicam.tcpimpact.model.MethodRange;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public class RevisionMethodExtractor {
    private final FileReader fileReader;
    private final MethodExtractor methodExtractor;

    public RevisionMethodExtractor() {
        this.fileReader = new FileReader();
        this.methodExtractor = new MethodExtractor();
    }

    public List<MethodRange> extractMethodsAtRevision(Path path, String revision, Path relativePath) throws IOException{
        String sourceCode = fileReader.readFileAtRevision(path, revision, relativePath);
        return methodExtractor.extractMethods(sourceCode, relativePath);
    }
}
