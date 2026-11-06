package com.elixir.biohackaton.ISAToSRA;

import com.elixir.biohackaton.ISAToSRA.controller.WebinIsaToXmlSubmissionController;
import com.elixir.biohackaton.ISAToSRA.sra.service.WebinExperimentXmlCreator;
import com.elixir.biohackaton.ISAToSRA.sra.service.WebinRunXmlCreator;
import com.elixir.biohackaton.ISAToSRA.sra.service.WebinStudyXmlCreator;
import com.elixir.mars.repository.models.isa.IsaJson;
import com.elixir.mars.repository.models.isa.Study;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.util.List;
import java.util.Map;
import org.dom4j.Document;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.dom4j.io.OutputFormat;
import org.dom4j.io.XMLWriter;

/**
 * Builds the ENA XML the same way as WebinIsaToXmlSubmissionController.performSubmissionToEna,
 * but stops before the Webin submission. Copied into the MARS ENA adapter by run.sh.
 */
public class MetatrackOfflineXmlTest {
  public static void main(String[] args) throws Exception {
    // Same as the Spring Boot default mapper used by MARS.
    ObjectMapper mapper =
        new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    JsonNode root = mapper.readTree(new File(args[0]));
    // Metatrack exports the Investigation itself; MARS expects {"investigation": ...}.
    if (!root.has("investigation")) {
      root = mapper.createObjectNode().set("investigation", root);
    }
    List<Study> studies = mapper.treeToValue(root, IsaJson.class).getInvestigation().getStudies();

    Document document = DocumentHelper.createDocument();
    Element webin = document.addElement("WEBIN");
    webin.addElement("SUBMISSION").addElement("ACTIONS").addElement("ACTION").addElement("ADD");
    String submissionId = "offline";

    new WebinStudyXmlCreator().createENAStudySetElement(webin, studies, submissionId);
    Map<String, String> sampleAccessions;
    try {
      sampleAccessions = new WebinIsaToXmlSubmissionController().getBiosamples(studies);
    } catch (IllegalArgumentException e) {
      // Normally the BioSamples adapter provides this; it is not run here.
      System.err.println("No BioSamples accession in the export, using SAMEA0000000");
      sampleAccessions = Map.of("SOURCE", "SAMEA0000000");
    }
    Map<String, String> experiments =
        new WebinExperimentXmlCreator()
            .createENAExperimentSetElement(sampleAccessions, webin, studies, submissionId);
    new WebinRunXmlCreator().createENARunSetElement(webin, studies, experiments, submissionId);

    new XMLWriter(System.out, OutputFormat.createPrettyPrint()).write(document);
    System.out.printf(
        "%nENA XML: %d studies, %d experiments, %d runs (nothing submitted)%n",
        count(webin, "STUDY"), count(webin, "EXPERIMENT"), count(webin, "RUN"));
  }

  private static int count(Element element, String name) {
    int n = element.getName().equals(name) ? 1 : 0;
    for (Object child : element.elements()) {
      n += count((Element) child, name);
    }
    return n;
  }
}
