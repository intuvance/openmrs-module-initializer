package org.openmrs.module.initializer.api.drugs;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.openmrs.ConceptMapType;
import org.openmrs.ConceptReferenceTerm;
import org.openmrs.ConceptSource;
import org.openmrs.Drug;
import org.openmrs.DrugReferenceMap;
import org.openmrs.api.ConceptService;
import org.openmrs.module.initializer.api.BaseLineProcessor;
import org.openmrs.module.initializer.api.CsvLine;
import org.openmrs.module.initializer.api.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component("initializer.mappingsDrugLineProcessor")
public class MappingsDrugLineProcessor extends DrugLineProcessor {
	
	protected final Logger log = LoggerFactory.getLogger(getClass());
	
	public static final String MAPPING_HEADER_PREFIX = "mappings";
	
	public static final String MAPPING_HEADER_SEPARATOR_REGEX = "\\|";
	
	@Autowired
	public MappingsDrugLineProcessor(@Qualifier("conceptService") ConceptService conceptService) {
		super(conceptService);
	}
	
	public Drug fill(Drug drug, CsvLine line) throws IllegalArgumentException {
		
		// Intuvance change: parse the line first and mutate the drug only once it is known what the
		// line actually declared.
		//
		// Upstream cleared drug.getDrugReferenceMaps() before it looked at the line at all. This is
		// the same destructive bug already fixed in MappingsConceptLineProcessor, one class over: a
		// drugs.csv row that declares no mappings -- or that declares none at all, because the file
		// has no mapping columns -- destroyed the drug's existing reference maps, which is how a
		// drug resolves from a code such as an RxNorm or a local formulary code. A row that failed
		// validation part-way through, for an unresolvable concept source or map type, took them down
		// with it as well, because they were already gone by the time the exception was thrown.
		//
		// With this change:
		//   * a line that declares no mappings leaves existing reference maps untouched
		//   * a line that declares some still replaces them wholesale, so packages that do author
		//     mappings keep the upstream "the CSV wins" semantics exactly
		List<DrugReferenceMap> declared = new ArrayList<DrugReferenceMap>();
		
		for (String header : line.getHeaderLine()) {
			
			String lineValue = line.get(header);
			if (!StringUtils.isEmpty(lineValue)) {
				
				// There are up to 4 components.  Mappings|Type|Source|Suffix, where only the first 2 are required
				String[] headerComponents = header.split(MAPPING_HEADER_SEPARATOR_REGEX, 4);
				
				if (headerComponents[0].equalsIgnoreCase(MAPPING_HEADER_PREFIX)) {
					
					// First determine the map type (required)
					if (headerComponents.length < 2) {
						throw new IllegalArgumentException("Missing concept map type in header '" + header + "'");
					}
					String headerMapTypeStr = headerComponents[1].trim().toLowerCase();
					ConceptMapType mapType = Utils.fetchConceptMapType(headerMapTypeStr, conceptService);
					if (mapType == null) {
						throw new IllegalArgumentException("Unable to determine concept map type '" + headerMapTypeStr
						        + "' defined in header '" + header + "'");
					}
					
					// Next, if the source prefix is specified in the header, rather than in the value, retrieve it
					ConceptSource conceptSource = null;
					if (headerComponents.length > 2) {
						String source = headerComponents[2].trim();
						conceptSource = Utils.fetchConceptSource(source, conceptService);
						if (conceptSource == null) {
							throw new IllegalArgumentException(
							        "Unable to find concept source '" + source + "' defined in header '" + header + "'");
						}
					}
					
					// Each column can support a delimited list of mappings for the given header
					// If the header does not contain a fixed source, then these must be specified in each value
					// using the source:code format
					for (String val : lineValue.split(BaseLineProcessor.LIST_SEPARATOR)) {
						ConceptSource source = conceptSource;
						String code = val;
						if (source == null) {
							String[] sourceAndCode = val.split(":", 2);
							if (sourceAndCode.length != 2) {
								throw new IllegalArgumentException("Drug mapping '" + val + "' is missing "
								        + "concept source. Expected format is 'source:code'");
							}
							String sourceLookup = sourceAndCode[0].trim();
							source = Utils.fetchConceptSource(sourceLookup, conceptService);
							if (source == null) {
								throw new IllegalArgumentException("Unable to find concept source '" + sourceLookup
								        + "' defined in drug mapping '" + val + "'");
							}
							code = sourceAndCode[1].trim();
						}
						
						ConceptReferenceTerm refTerm = conceptService.getConceptReferenceTermByCode(code, source);
						if (refTerm == null) {
							refTerm = new ConceptReferenceTerm(source, code, "");
						}
						
						DrugReferenceMap map = new DrugReferenceMap();
						map.setDrug(drug);
						map.setConceptMapType(mapType);
						map.setConceptReferenceTerm(refTerm);
						
						declared.add(map);
					}
				}
			}
		}
		
		if (declared.isEmpty()) {
			if (!CollectionUtils.isEmpty(drug.getDrugReferenceMaps())) {
				log.debug("Line declares no drug reference maps; preserving the {} existing reference map(s) of drug {}",
				    drug.getDrugReferenceMaps().size(), drug.getUuid());
			}
			return drug;
		}
		
		if (!CollectionUtils.isEmpty(drug.getDrugReferenceMaps())) {
			drug.getDrugReferenceMaps().clear();
		}
		
		for (DrugReferenceMap map : declared) {
			drug.addDrugReferenceMap(map);
		}
		
		return drug;
	}
}
