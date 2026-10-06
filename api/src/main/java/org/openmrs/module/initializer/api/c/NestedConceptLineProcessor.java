package org.openmrs.module.initializer.api.c;

import org.apache.commons.lang3.StringUtils;
import org.openmrs.Concept;
import org.openmrs.ConceptAnswer;
import org.openmrs.api.ConceptService;
import org.openmrs.module.initializer.api.CsvLine;
import org.openmrs.module.initializer.api.utils.ConceptListParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.Collections;
import java.util.List;

@Component("initializer.nestedConceptLineProcessor")
public class NestedConceptLineProcessor extends ConceptLineProcessor {
	
	protected static String HEADER_ANSWERS = "answers";
	
	protected static String HEADER_MEMBERS = "members";
	
	protected ConceptListParser listParser;
	
	@Autowired
	public NestedConceptLineProcessor(@Qualifier("conceptService") ConceptService conceptService,
	    ConceptListParser listParser) {
		super(conceptService);
		this.listParser = listParser;
	}
	
	public Concept fill(Concept concept, CsvLine line) throws IllegalArgumentException {
		
		// Intuvance change: work out what the line actually declares before mutating the concept.
		//
		// Upstream cleared concept.getAnswers() and concept.getConceptSets() as soon as it saw the
		// relevant column in the header, before it had read the value for this row. conceptSets is
		// the live membership collection behind getSetMembers() and addSetMember(), so that clear()
		// does not merely ignore a row, it deletes the concept set membership the concept already
		// had.
		//
		// That is destructive for a distribution seeded from a pre-populated database. A content
		// package that carries a Members column -- which it must, to declare membership for even one
		// concept -- but leaves the cell blank for a concept that is already a member of a set wipes
		// that membership, and it also resets the concept's set flag via setSet(false). Concept sets
		// are what drive panels, decision support and everything else that enumerates a set's
		// members, so they go quietly missing on a first boot after seeding.
		//
		// With this change:
		//   * a row that declares no members leaves existing membership and the set flag untouched
		//   * a row that declares some still replaces them wholesale, so packages that do author
		//     membership keep the upstream "the CSV wins" semantics exactly
		//   * the same holds for answers
		List<Concept> declaredAnswers = parseChildren(line, HEADER_ANSWERS);
		if (!declaredAnswers.isEmpty()) {
			if (!CollectionUtils.isEmpty(concept.getAnswers())) {
				concept.getAnswers().clear();
			}
			for (Concept child : declaredAnswers) {
				concept.addAnswer(new ConceptAnswer(child));
			}
		}
		
		List<Concept> declaredMembers = parseChildren(line, HEADER_MEMBERS);
		if (!declaredMembers.isEmpty()) {
			if (!CollectionUtils.isEmpty(concept.getConceptSets())) {
				concept.getConceptSets().clear();
				concept.setSet(false);
			}
			for (Concept child : declaredMembers) {
				concept.addSetMember(child);
			}
			concept.setSet(true);
		}
		
		return concept;
	}
	
	/**
	 * Resolves the comma or semicolon separated children the given column declares for this row, or an
	 * empty list if it declares none. Resolving first is what lets the caller tell "this row says
	 * nothing" apart from "this row restates it".
	 */
	private List<Concept> parseChildren(CsvLine line, String header) {
		
		String childrenStr = line.get(header);
		if (StringUtils.isEmpty(childrenStr)) {
			return Collections.emptyList();
		}
		return listParser.parseList(childrenStr);
	}
}
