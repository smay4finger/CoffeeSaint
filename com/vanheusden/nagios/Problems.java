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

	public int compare(Problem a, Problem b) {
		int result = 0;
		Problem pa = (Problem)a;
		Problem pb = (Problem)b;

		if (sortField.equals("host_name")) {
			result = pa.getHost().getHostName().compareTo(pb.getHost().getHostName());
		}
		else if (sortField.equals("pretty_name") || sortField.equals("server_name")) {
			result = pa.getHost().getNagiosSource().compareTo(pb.getHost().getNagiosSource());
		}
		else {
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

	static void addProblem(List<Pattern> prioPatterns, List<Problem> problems, List<Problem> lessImportant, Host host, Service service, String state, boolean hard)
	{
		boolean important = false;

		if (prioPatterns != null)
		{
			String msg = host.getHostName() + ": " + (service != null ? service.getServiceName() : "");

			for(Pattern currentPattern : prioPatterns)
			{
				if (currentPattern.matcher(msg).find())
				{
					important = true;
					break;
				}
			}
		}

		if (important)
			problems.add(new Problem(host, service, state, hard));
		else
			lessImportant.add(new Problem(host, service, state, hard));
	}

	public static boolean filter(List<Pattern> filterExclude, List<Pattern> filterInclude, String pattern)
	{
		boolean add = true;

//System.out.println("---");
		if (filterExclude != null)
		{
//System.out.println("filterExclude");
			for(Pattern currentPattern : filterExclude)
			{
//System.out.println(" pattern: " + currentPattern.pattern() + ": " + currentPattern.matcher(pattern).find());
				if (currentPattern.matcher(pattern).find())
					add = false;
			}
		}
		if (filterInclude != null && add == false)
		{
//System.out.println("filterInclude");
			for(Pattern currentPattern : filterInclude)
			{
//System.out.println(" pattern: " + currentPattern.pattern() + ": " + currentPattern.matcher(pattern).find());
				if (currentPattern.matcher(pattern).find())
					add = true;
			}
		}

//System.out.println(" ADD: " + add + ": " + pattern);
		return add;
	}

	public static void collectProblems(JavNag javNag, List<Pattern> prioPatterns, List<Problem> prioProblems, List<Problem> lessImportant, boolean always_notify, boolean also_acknowledged, boolean also_scheduled_downtime, boolean also_soft_state, boolean also_disabled_active_checks, boolean show_services_from_host_with_problems, boolean display_flapping, List<Pattern> hostsFilterExclude, List<Pattern> hostsFilterInclude, List<Pattern> servicesFilterExclude, List<Pattern> servicesFilterInclude, boolean host_scheduled_downtime_show_services, boolean host_acknowledged_show_services, boolean host_scheduled_downtime_or_ack_show_services, boolean display_unknown, boolean display_down)
	{
//System.out.println("host_scheduled_downtime_show_services: " + host_scheduled_downtime_show_services);
//System.out.println("host_acknowledged_show_services: " + host_acknowledged_show_services);
		for(Host currentHost: javNag.getListOfHosts())
		{
			final String hostName = currentHost.getHostName();
			boolean showHost = false, showServices = true;
			String hostState = currentHost.getParameter("current_state");

			if (javNag.shouldIShowHost(currentHost, always_notify, also_acknowledged, also_scheduled_downtime, also_soft_state, also_disabled_active_checks, display_flapping, display_unknown, display_down))
			{
				boolean hard = currentHost.getParameter("state_type").equals("1");
				String useState = null;

				if (hostState.equals("0")) /* UP = OK */
					useState = "0";
				else if (hostState.equals("1") || hostState.equals("2")) /* DOWN & UNREACHABLE = CRITICAL */
					useState = "2";
				else /* all other states (including 'pending' ("3")) are WARNING */
					useState = "1";

				boolean add = filter(hostsFilterExclude, hostsFilterInclude, hostName);

				if (add)
					addProblem(prioPatterns, prioProblems, lessImportant, currentHost, null, useState, hard);

				showHost = true;
			}

// System.out.println(hostName + " hoststate " + hostState + " bla");
			boolean host_scheduled_downtime = Double.valueOf(currentHost.getParameter("scheduled_downtime_depth")) != 0.0;
			boolean host_has_acked = currentHost.getParameter("problem_has_been_acknowledged").equals("1") == true;
			if (hostState.equals("0") == false) {
//System.out.println(hostName + " error state " + host_scheduled_downtime_show_services + " " + host_scheduled_downtime + " " + host_acknowledged_show_services + " " + host_has_acked);
				if (!host_scheduled_downtime_show_services && host_scheduled_downtime)
					showServices = false;
				if (!host_acknowledged_show_services && host_has_acked)
					showServices = false;
			}

			// System.out.println("flags: " + host_scheduled_downtime_or_ack_show_services + " " + host_scheduled_downtime + " " + host_has_acked + " showservices: "+ showServices);
			if (host_scheduled_downtime_or_ack_show_services == false && (host_scheduled_downtime || host_has_acked))
				showServices = false;

//System.out.println(hostName + " " + showHost + " " + show_services_from_host_with_problems + " " + showServices);
//System.out.println("" + Double.valueOf(currentHost.getParameter("scheduled_downtime_depth")) + " " + currentHost.getParameter("problem_has_been_acknowledged").equals("1"));
			if ((showHost == false || show_services_from_host_with_problems == true) && showServices)
			{
//System.out.println("check services");
				for(Service currentService : currentHost.getServices())
				{
// System.out.println("service: " + currentService.getServiceName());
					assert currentService != null;
					if (javNag.shouldIShowService(currentService, always_notify, also_acknowledged, also_scheduled_downtime, also_soft_state, also_disabled_active_checks, display_flapping, display_unknown))
					{
//System.out.println("SHOW SERVICE");
						String state = currentService.getParameter("current_state");
						String serviceName = currentService.getServiceName();
						boolean hard = currentService.getParameter("state_type").equals("1");

						boolean add = filter(hostsFilterExclude, hostsFilterInclude, hostName);

						if (add)
							add = filter(servicesFilterExclude, servicesFilterInclude, serviceName);

						if (add)
							addProblem(prioPatterns, prioProblems, lessImportant, currentHost, currentService, state, hard);
					}
				}
			}
		}
	}
}
