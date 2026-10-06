package org.openmrs.module.initializer.api.c;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.openmrs.Concept;
import org.openmrs.ConceptAnswer;
import org.openmrs.api.ConceptService;
import org.openmrs.module.initializer.api.CsvLine;
import org.openmrs.module.initializer.api.utils.ConceptListParser;

/*
 * This kind of test case can be used to quickly trial the parsing routines on test CSVs
 */
public class NestedConceptLineProcessorTest {
	
	private ConceptService cs = mock(ConceptService.class);
	
	@Before
	public void setup() {
		
		/*
		 * fetching a concept by mapping returns a concept with the mapping as uuid this
		 * allows to verifies that the correct children are indeed found in collections
		 */
		when(cs.getConceptByMapping(any(String.class), any(String.class))).thenAnswer(new Answer<Concept>() {
			
			@Override
			public Concept answer(InvocationOnMock invocation) throws Throwable {
				Object[] args = invocation.getArguments();
				String code = (String) args[0];
				String source = (String) args[1];
				Concept c = new Concept();
				c.setUuid(source + ":" + code);
				return c;
			}
		});
	}
	
	@Test
	public void fill_shouldParseAnswers() {
		
		// Setup
		String[] headerLine = { "Answers", "Members" };
		String[] line = { "cambodia:123; cambodia:456", null };
		
		// Replay
		NestedConceptLineProcessor p = new NestedConceptLineProcessor(cs, new ConceptListParser(cs));
		Concept c = p.fill(new Concept(), new CsvLine(headerLine, line));
		
		// Verif
		Assert.assertFalse(c.getSet());
		Collection<ConceptAnswer> answers = c.getAnswers();
		Assert.assertEquals(2, answers.size());
		Set<String> uuids = new HashSet<String>();
		for (ConceptAnswer a : answers) {
			uuids.add(a.getAnswerConcept().getUuid());
		}
		Assert.assertTrue(uuids.contains("cambodia:123"));
		Assert.assertTrue(uuids.contains("cambodia:456"));
	}
	
	@Test
	public void fill_shouldParseSetMembers() {
		
		// Setup
		String[] headerLine = { "Answers", "Members" };
		String[] line = { null, "cambodia:123; cambodia:456" };
		
		// Replay
		NestedConceptLineProcessor p = new NestedConceptLineProcessor(cs, new ConceptListParser(cs));
		Concept c = p.fill(new Concept(), new CsvLine(headerLine, line));
		
		// Verif
		Assert.assertTrue(c.getSet());
		List<Concept> members = c.getSetMembers();
		Assert.assertEquals(2, members.size());
		Set<String> uuids = new HashSet<String>();
		for (Concept cpt : members) {
			uuids.add(cpt.getUuid());
		}
		Assert.assertTrue(uuids.contains("cambodia:123"));
		Assert.assertTrue(uuids.contains("cambodia:456"));
	}
	
	@Test
	public void fill_shouldHandleNoChildren() {
		
		// Setup
		String[] headerLine = { "Answers", "Members" };
		String[] line = { null, null };
		
		// Replay
		NestedConceptLineProcessor p = new NestedConceptLineProcessor(cs, new ConceptListParser(cs));
		Concept c = p.fill(new Concept(), new CsvLine(headerLine, line));
		
		// Verif
		Assert.assertFalse(c.getSet());
		Assert.assertEquals(0, c.getSetMembers().size());
		Assert.assertEquals(0, c.getAnswers().size());
	}
	
	public void fill_shouldHandleMissingHeaders() {
		
		// Setup
		String[] headerLine = {};
		String[] line = {};
		
		// Replay
		NestedConceptLineProcessor p = new NestedConceptLineProcessor(cs, new ConceptListParser(cs));
		Concept c = p.fill(new Concept(), new CsvLine(headerLine, line));
		Assert.assertNull(c.getAnswers());
		Assert.assertNull(c.getSetMembers());
	}
	
	/*
	 * Regression coverage for the change that made this processor non-destructive.
	 *
	 * Upstream cleared concept.getConceptSets() and concept.getAnswers() as soon as the header line
	 * contained the relevant column, before reading this row's value. conceptSets is the live
	 * collection behind getSetMembers(), so on a seeded database that deletes concept set membership
	 * the concept already had -- and resets the set flag with it.
	 */
	
	@Test
	public void fill_shouldPreserveExistingSetMembersWhenTheRowDeclaresNone() {
		
		// Setup: a concept that is already a member of a set, from a previous install
		String[] headerLine = { "Answers", "Members" };
		String[] line = { null, null };
		
		Concept c = conceptWithSetMembers("cambodia:123");
		
		// Replay
		NestedConceptLineProcessor p = new NestedConceptLineProcessor(cs, new ConceptListParser(cs));
		p.fill(c, new CsvLine(headerLine, line));
		
		// Verif: the membership that was already there survives
		Assert.assertEquals(1, c.getSetMembers().size());
		Assert.assertTrue(memberUuids(c).contains("cambodia:123"));
	}
	
	@Test
	public void fill_shouldPreserveExistingSetMembersWhenNoMemberColumnIsPresent() {
		
		// Setup: a content package row that only carries, say, a name
		String[] headerLine = { "Fully specified name:en" };
		String[] line = { "Bronchospasm" };
		
		Concept c = conceptWithSetMembers("cambodia:123");
		
		// Replay
		NestedConceptLineProcessor p = new NestedConceptLineProcessor(cs, new ConceptListParser(cs));
		p.fill(c, new CsvLine(headerLine, line));
		
		// Verif
		Assert.assertEquals(1, c.getSetMembers().size());
		Assert.assertTrue(memberUuids(c).contains("cambodia:123"));
	}
	
	@Test
	public void fill_shouldPreserveTheSetFlagWhenTheRowDeclaresNoMembers() {
		
		// Setup: upstream also called setSet(false) here, so a concept that is a set stopped being
		// treated as one
		String[] headerLine = { "Answers", "Members" };
		String[] line = { null, null };
		
		Concept c = conceptWithSetMembers("cambodia:123");
		c.setSet(true);
		
		// Replay
		NestedConceptLineProcessor p = new NestedConceptLineProcessor(cs, new ConceptListParser(cs));
		p.fill(c, new CsvLine(headerLine, line));
		
		// Verif
		Assert.assertTrue(c.getSet());
	}
	
	@Test
	public void fill_shouldPreserveEveryPreExistingSetMemberNotJustTheFirst() {
		
		// Setup: the realistic case is a panel with many members
		String[] headerLine = { "Answers", "Members" };
		String[] line = { null, null };
		
		Concept c = conceptWithSetMembers("cambodia:123", "cambodia:456", "CIEL:789", "CIEL:abc");
		
		// Replay
		NestedConceptLineProcessor p = new NestedConceptLineProcessor(cs, new ConceptListParser(cs));
		p.fill(c, new CsvLine(headerLine, line));
		
		// Verif: all four survive
		Assert.assertEquals(4, c.getSetMembers().size());
	}
	
	@Test
	public void fill_shouldPreserveExistingAnswersWhenTheRowDeclaresNone() {
		
		// Setup
		String[] headerLine = { "Answers", "Members" };
		String[] line = { null, null };
		
		Concept c = new Concept();
		c.addAnswer(new ConceptAnswer(concept("cambodia:123")));
		c.addAnswer(new ConceptAnswer(concept("cambodia:456")));
		
		// Replay
		NestedConceptLineProcessor p = new NestedConceptLineProcessor(cs, new ConceptListParser(cs));
		p.fill(c, new CsvLine(headerLine, line));
		
		// Verif
		Assert.assertEquals(2, c.getAnswers().size());
	}
	
	@Test
	public void fill_shouldStillReplaceExistingSetMembersWhenTheRowDeclaresThem() {
		
		// Setup: this is the upstream semantic, and it must not regress. A package that genuinely
		// authors membership stays authoritative over it.
		String[] headerLine = { "Answers", "Members" };
		String[] line = { null, "cambodia:999" };
		
		Concept c = conceptWithSetMembers("cambodia:123");
		
		// Replay
		NestedConceptLineProcessor p = new NestedConceptLineProcessor(cs, new ConceptListParser(cs));
		p.fill(c, new CsvLine(headerLine, line));
		
		// Verif: replaced, not merged
		Assert.assertEquals(1, c.getSetMembers().size());
		Assert.assertTrue(memberUuids(c).contains("cambodia:999"));
		Assert.assertFalse(memberUuids(c).contains("cambodia:123"));
	}
	
	@Test
	public void fill_shouldStillReplaceExistingAnswersWhenTheRowDeclaresThem() {
		
		// Setup
		String[] headerLine = { "Answers", "Members" };
		String[] line = { "cambodia:999", null };
		
		Concept c = new Concept();
		c.addAnswer(new ConceptAnswer(concept("cambodia:123")));
		
		// Replay
		NestedConceptLineProcessor p = new NestedConceptLineProcessor(cs, new ConceptListParser(cs));
		p.fill(c, new CsvLine(headerLine, line));
		
		// Verif
		Assert.assertEquals(1, c.getAnswers().size());
	}
	
	@Test
	public void fill_shouldStillSetTheSetFlagWhenTheRowDeclaresMembers() {
		
		// Setup: the counterpart of the preservation case, so the flag is not simply never set again
		String[] headerLine = { "Answers", "Members" };
		String[] line = { null, "cambodia:999" };
		
		Concept c = new Concept();
		c.setSet(false);
		
		// Replay
		NestedConceptLineProcessor p = new NestedConceptLineProcessor(cs, new ConceptListParser(cs));
		p.fill(c, new CsvLine(headerLine, line));
		
		// Verif
		Assert.assertTrue(c.getSet());
	}
	
	@Test
	public void fill_shouldNotClearSetMembersWhenAMemberCannotBeResolved() {
		
		// Setup: an unresolvable child. Upstream cleared the membership first and then threw, so a
		// typo in a content package silently emptied the concept set along with rejecting the row.
		// Thrown from the mock rather than returned as null, because a null would send
		// Utils.fetchConcept off to look the name up as a fully specified name through the OpenMRS
		// Context, which is not available in a plain unit test.
		when(cs.getConceptByMapping("no-such-code", "no-such-source"))
		        .thenThrow(new IllegalArgumentException("no such code"));
		
		String[] headerLine = { "Answers", "Members" };
		String[] line = { null, "no-such-source:no-such-code" };
		
		Concept c = conceptWithSetMembers("cambodia:123");
		
		// Replay and verify the row still fails
		NestedConceptLineProcessor p = new NestedConceptLineProcessor(cs, new ConceptListParser(cs));
		try {
			p.fill(c, new CsvLine(headerLine, line));
			Assert.fail("expected an IllegalArgumentException for the unresolvable child");
		}
		catch (IllegalArgumentException expected) {
			// the row is rejected...
		}
		
		// ...but the membership was not collateral damage
		Assert.assertEquals(1, c.getSetMembers().size());
		Assert.assertTrue(memberUuids(c).contains("cambodia:123"));
	}
	
	/**
	 * A concept that already belongs to the given set members, as a seeded database would have it.
	 */
	private static Concept conceptWithSetMembers(String... sourceAndCodes) {
		
		Concept c = new Concept();
		c.setSet(true);
		for (String sourceAndCode : sourceAndCodes) {
			c.addSetMember(concept(sourceAndCode));
		}
		return c;
	}
	
	private static Concept concept(String sourceAndCode) {
		
		Concept c = new Concept();
		c.setUuid(sourceAndCode);
		return c;
	}
	
	private static Set<String> memberUuids(Concept c) {
		
		Set<String> uuids = new HashSet<String>();
		for (Concept member : c.getSetMembers()) {
			uuids.add(member.getUuid());
		}
		return uuids;
	}
}
