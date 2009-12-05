/* Released under the GPL2. See license.txt for details. */
package com.vanheusden.nagios;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

public class Problems implements Comparator<Problem>
{
	String sortField;
	boolean sortNumeric, sortReverse;

	public Problems(String field, boolean numeric, boolean reverse)
	{
		this.sortField   = field;
		this.sortNumeric = numeric;
		this.sortReverse = reverse;
	}

	public int compare(Problem a, Problem b)
	{
		int result = 0;
		Problem pa = (Problem)a;
		Problem pb = (Problem)b;

		if (sortField.equals("host_name"))
		{
			result = pa.getHost().getHostName().compareTo(pb.getHost().getHostName());
		}
		else
		{
			Service pas = pa.getService();
			Service pbs = pb.getService();

			if (pas != null && pbs != null)
			{
				if (sortNumeric)
					result = (int)(Long.valueOf(pbs.getParameter(sortField)) - Long.valueOf(pas.getParameter(sortField)));
				else
					result = pas.getParameter(sortField).compareTo(pbs.getParameter(sortField));
			}
			else
			{
				Host pah = pa.getHost();
				Host pbh = pb.getHost();

				if (sortNumeric)
					result = (int)(Long.valueOf(pbh.getParameter(sortField)) - Long.valueOf(pah.getParameter(sortField)));
				else
					result = pah.getParameter(sortField).compareTo(pbh.getParameter(sortField));
			}
		}

		if (sortReverse)
			result = -result;

		return result;
	}

	public static void sortList(List<Problem> problems, String field, boolean numeric, boolean reverse)
	{
		Collections.sort(problems, new Problems(field, numeric, reverse));
	}

	static void addProblem(List<Pattern> prioPatterns, List<Problem> problems, List<Problem> lessImportant, Host host, Service service, String state)
	{
		boolean important = false;

		if (prioPatterns != null)
		{
			String msg = host.getHostName() + ": " + (service != null ? service.getServiceName() : "");

			for(Pattern currentPattern : prioPatterns)
			{
				if (currentPattern.matcher(msg).matches())
				{
					important = true;
					break;
				}
			}
		}

		if (important)
			problems.add(new Problem(host, service, state));
		else
			lessImportant.add(new Problem(host, service, state));
	}

	public static void collectProblems(JavNag javNag, List<Pattern> prioPatterns, List<Problem> prioProblems, List<Problem> lessImportant, boolean always_notify, boolean also_acknowledged, boolean also_scheduled_downtime, boolean also_soft_state, boolean also_disabled_active_checks, boolean show_services_from_host_with_problems)
	{
		for(Host currentHost: javNag.getListOfHosts())
		{
			boolean showHost = false;
			assert currentHost != null;

			if (javNag.shouldIShowHost(currentHost, always_notify, also_acknowledged, also_scheduled_downtime, also_soft_state, also_disabled_active_checks))
			{
				String useState = null;
				String state = currentHost.getParameter("current_state");

				if (state.equals("0")) /* UP = OK */
					useState = "0";
				else if (state.equals("1") || state.equals("2")) /* DOWN & UNREACHABLE = CRITICAL */
					useState = "2";
				else /* all other states (including 'pending' ("3")) are WARNING */
					useState = "1";

				addProblem(prioPatterns, prioProblems, lessImportant, currentHost, null, useState);

				showHost = true;
			}

			if (showHost == false || show_services_from_host_with_problems == true)
			{
				for(Service currentService : currentHost.getServices())
				{
					assert currentService != null;
					if (javNag.shouldIShowService(currentService, always_notify, also_acknowledged, also_scheduled_downtime, also_soft_state, also_disabled_active_checks))
					{
						String state = currentService.getParameter("current_state");

						addProblem(prioPatterns, prioProblems, lessImportant, currentHost, currentService, state);
					}
				}
			}
		}
	}
}
