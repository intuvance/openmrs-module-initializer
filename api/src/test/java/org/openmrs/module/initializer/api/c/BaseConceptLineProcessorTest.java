package org.openmrs.module.initializer.api.c;

import static org.mockito.Mockito.mock;

import java.util.Locale;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.LocaleUtils;
import org.junit.Assert;
import org.junit.Test;
import org.openmrs.Concept;
import org.openmrs.ConceptDescription;
import org.openmrs.api.ConceptService;
import org.openmrs.module.initializer.api.CsvLine;

/*
 * This kind of test case can be used to quickly trial the parsing routines on test CSVs
 */
public class BaseConceptLineProcessorTest {
	
	private ConceptService cs = mock(ConceptService.class);
	
	@Test
	public void fill_shouldHandleMissingHeaders() {
		
		// Setup
		String[] headerLine = {};
		String[] line = {};
		
		// Replay
		ConceptLineProcessor p = new ConceptLineProcessor(cs);
		Concept c = p.fill(new Concept(), new CsvLine(headerLine, line));
		
		// Verif
		Assert.assertTrue(CollectionUtils.isEmpty(c.getNames()));
		Assert.assertTrue(CollectionUtils.isEmpty(c.getDescriptions()));
		Assert.assertNull(c.getConceptClass());
		Assert.assertNull(c.getDatatype());
	}
	
	/*
	 * Regression coverage for the change that made this processor non-destructive.
	 *
	 * Upstream cleared concept.getDescriptions() unconditionally, before it had looked at the line --
	 * not even gated on a description column being present. So every row of every concepts.csv
	 * emptied the descriptions a seeded database already had.
	 */
	
	@Test
	public void fill_shouldPreserveExistingDescriptionsWhenTheRowDeclaresNone() {
		
		// Setup: a concept that already carries a description from a previous install
		String[] headerLine = { "Description:en" };
		String[] line = { null };
		
		Concept c = conceptWithDescriptions("en", "A preexisting description");
		
		// Replay
		ConceptLineProcessor p = new ConceptLineProcessor(cs);
		p.fill(c, new CsvLine(headerLine, line));
		
		// Verif
		Assert.assertEquals(1, c.getDescriptions().size());
		Assert.assertEquals("A preexisting description", c.getDescription(Locale.ENGLISH).getDescription());
	}
	
	@Test
	public void fill_shouldPreserveExistingDescriptionsWhenNoDescriptionColumnIsPresent() {
		
		// Setup: a content package row that only carries, say, a name. Upstream cleared the
		// descriptions here too, because the clear() was not conditional on anything.
		String[] headerLine = { "Fully specified name:en" };
		String[] line = { "Bronchospasm" };
		
		Concept c = conceptWithDescriptions("en", "A preexisting description");
		
		// Replay
		ConceptLineProcessor p = new ConceptLineProcessor(cs);
		p.fill(c, new CsvLine(headerLine, line));
		
		// Verif
		Assert.assertEquals(1, c.getDescriptions().size());
		Assert.assertEquals("A preexisting description", c.getDescription(Locale.ENGLISH).getDescription());
	}
	
	@Test
	public void fill_shouldPreserveEveryPreExistingDescriptionNotJustTheFirst() {
		
		// Setup: descriptions are per-locale, so a fully localized concept has one per locale
		String[] headerLine = {};
		String[] line = {};
		
		Concept c = conceptWithDescriptions("en", "English description", "fr", "Description en francais", "km_KH",
		    "ការពិពណ៌នាភាសាខ្មែរ");
		
		// Replay
		ConceptLineProcessor p = new ConceptLineProcessor(cs);
		p.fill(c, new CsvLine(headerLine, line));
		
		// Verif: all three survive
		Assert.assertEquals(3, c.getDescriptions().size());
	}
	
	@Test
	public void fill_shouldStillReplaceExistingDescriptionsWhenTheRowDeclaresThem() {
		
		// Setup: this is the upstream semantic, and it must not regress. A package that genuinely
		// authors descriptions stays authoritative over them.
		String[] headerLine = { "Description:en" };
		String[] line = { "A new description" };
		
		Concept c = conceptWithDescriptions("en", "A preexisting description");
		
		// Replay
		ConceptLineProcessor p = new ConceptLineProcessor(cs);
		p.fill(c, new CsvLine(headerLine, line));
		
		// Verif: replaced, not merged
		Assert.assertEquals(1, c.getDescriptions().size());
		Assert.assertEquals("A new description", c.getDescription(Locale.ENGLISH).getDescription());
	}
	
	@Test
	public void fill_shouldStillReplaceExistingDescriptionsAcrossEveryLocale() {
		
		// Setup: only the locales the row restates should be affected, exactly as upstream did it
		String[] headerLine = { "Description:en", "Description:fr" };
		String[] line = { "New english", "Nouveau francais" };
		
		Concept c = conceptWithDescriptions("en", "Old english", "fr", "Ancien francais", "km_KH", "ការពិពណ៌នាភាសាខ្មែរ");
		
		// Replay
		ConceptLineProcessor p = new ConceptLineProcessor(cs);
		p.fill(c, new CsvLine(headerLine, line));
		
		// Verif: upstream replaced all three, so this documents that the semantics are unchanged
		Assert.assertEquals(2, c.getDescriptions().size());
		Assert.assertEquals("New english", c.getDescription(Locale.ENGLISH).getDescription());
		Assert.assertEquals("Nouveau francais", c.getDescription(Locale.FRENCH).getDescription());
	}
	
	@Test
	public void fill_shouldStillAddADescriptionToANewConcept() {
		
		// Setup: the ordinary case, which must keep working
		String[] headerLine = { "Description:en" };
		String[] line = { "A new description" };
		
		// Replay
		ConceptLineProcessor p = new ConceptLineProcessor(cs);
		Concept c = p.fill(new Concept(), new CsvLine(headerLine, line));
		
		// Verif
		Assert.assertEquals(1, c.getDescriptions().size());
		Assert.assertEquals("A new description", c.getDescription(Locale.ENGLISH).getDescription());
	}
	
	private static Concept conceptWithDescriptions(Object... localeThenDescription) {
		
		Concept c = new Concept();
		for (int i = 0; i < localeThenDescription.length; i += 2) {
			Locale locale = LocaleUtils.toLocale((String) localeThenDescription[i]);
			c.addDescription(new ConceptDescription((String) localeThenDescription[i + 1], locale));
		}
		return c;
	}
}
