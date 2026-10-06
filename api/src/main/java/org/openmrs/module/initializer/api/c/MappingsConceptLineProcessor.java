package org.openmrs.module.initializer.api.c;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.openmrs.Concept;
import org.openmrs.ConceptMap;
import org.openmrs.ConceptMapType;
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

@Component("initializer.mappingsConceptLineProcessor")
public class MappingsConceptLineProcessor extends ConceptLineProcessor {
	
	protected final Logger log = LoggerFactory.getLogger(getClass());
	
	public static final String MAPPING_HEADER_PREFIX = "mappings";
	
	public static final String MAPPING_HEADER_SEPARATOR = "|";
	
	public static final String MAPPING_HEADER_SEPARATOR_REGEX = "\\|";
	
	public static final String HEADER_MAPPINGS_SAMEAS = "same as mappings";
	
	@Autowired
	public MappingsConceptLineProcessor(@Qualifier("conceptService") ConceptService conceptService) {
		super(conceptService);
	}
	
	public Concept fill(Concept concept, CsvLine line) throws IllegalArgumentException {
		
		// Intuvance change: parse the line first and mutate the concept only once it is known what
		// the line actually declared.
		//
		// Upstream cleared concept.getConceptMappings() before it looked at the line at all, on the
		// assumption that any CSV reaching this processor was authoritative for them. That
		// assumption does not hold for a distribution that starts from a pre-populated database. A
		// content package that only adds a name or a description to concepts which already carry
		// CIEL mappings destroys those mappings without ever restating them, and the CSV grammar
		// cannot restate them either, because it allows only one mapping per column. A pre-populated
		// Reference Application with ~19,000 CIEL mappings therefore loses all but a few hundred on
		// its first boot, silently, after which every feature that resolves a concept by an external
		// code -- drug and lab order entry, medication workflows, concept search, FHIR concept
		// translation -- returns nothing.
		//
		// With this change:
		//   * a line that declares no mappings leaves the concept's existing mappings untouched
		//   * a line that declares some still replaces them wholesale, so content packages that do
		//     author mappings keep the upstream "the CSV wins" semantics exactly
		//   * a line that turns out to be invalid part-way through no longer takes the concept's
		//     mappings down with it. Previously they were cleared first and the exception left the
		//     concept stripped, so one typo in a content package silently cost it its terminology.
		List<ConceptMap> declared = new ArrayList<ConceptMap>();
		
		for (String header : line.getHeaderLine()) {
			
			String lineValue = line.get(header);
			if (!StringUtils.isEmpty(lineValue)) {
				
				// For backwards-compatibility, support the original same as mappings column header
				if (header.trim().equalsIgnoreCase(HEADER_MAPPINGS_SAMEAS)) {
					header = MAPPING_HEADER_PREFIX + MAPPING_HEADER_SEPARATOR + ConceptMapType.SAME_AS_MAP_TYPE_UUID;
				}
				
				// There are up to 4 components.  Mappings|Type|Source|Suffix, where only the first 2 are required
				String[] headerComponents = header.split(MAPPING_HEADER_SEPARATOR_REGEX, 4);
				
				if (headerComponents[0].equalsIgnoreCase(MAPPING_HEADER_PREFIX)) {
					
					// First determine the map type (required)
					String headerMapTypeStr = headerComponents[1].trim().toLowerCase();
					ConceptMapType mapType = Utils.fetchConceptMapType(headerMapTypeStr, conceptService);
					if (mapType == null) {
						throw new IllegalArgumentException("Unable to determine concept map type: " + headerMapTypeStr);
					}
					
					String sourcePrefix = "";
					if (headerComponents.length > 2) {
						sourcePrefix = headerComponents[2].trim() + ":";
					}
					
					// Each column can support a delimited list of mappings for the given header
					// Construct each mapping based on by source:code.  Source could come from header or value
					for (String val : lineValue.split(BaseLineProcessor.LIST_SEPARATOR)) {
						String m = sourcePrefix + val.trim();
						ConceptMap cm = new Utils.ConceptMappingWrapper(m, mapType, conceptService).getConceptMapping();
						declared.add(cm);
					}
				}
			}
		}
		
		if (declared.isEmpty()) {
			if (!CollectionUtils.isEmpty(concept.getConceptMappings())) {
				log.debug("Line declares no concept mappings; preserving the {} existing reference mapping(s) of concept {}",
				    concept.getConceptMappings().size(), concept.getUuid());
			}
			return concept;
		}
		
		if (!CollectionUtils.isEmpty(concept.getConceptMappings())) {
			concept.getConceptMappings().clear();
		}
		
		for (ConceptMap cm : declared) {
			concept.addConceptMapping(cm);
		}
		
		return concept;
	}
}
