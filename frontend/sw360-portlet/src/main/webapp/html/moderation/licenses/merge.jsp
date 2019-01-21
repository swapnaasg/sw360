<%--
  ~ Copyright Siemens AG, 2013-2016, 2019. Part of the SW360 Portal Project.
  ~ With modifications by Bosch Software Innovations GmbH, 2016.
  ~
  ~ SPDX-License-Identifier: EPL-1.0
  ~
  ~ All rights reserved. This program and the accompanying materials
  ~ are made available under the terms of the Eclipse Public License v1.0
  ~ which accompanies this distribution, and is available at
  ~ http://www.eclipse.org/legal/epl-v10.html
  --%>
<%@include file="/html/init.jsp"%>
<%-- the following is needed by liferay to display error messages--%>
<%@include file="/html/utils/includes/errorKeyToMessage.jspf"%>


<portlet:defineObjects />
<liferay-theme:defineObjects />

<%@ page import="org.eclipse.sw360.datahandler.thrift.moderation.DocumentType" %>
<%@ page import="org.eclipse.sw360.portal.common.PortalConstants" %>
<%@ page import="javax.portlet.PortletRequest" %>
<%@ page import="com.liferay.portlet.PortletURLFactoryUtil" %>

<jsp:useBean id="moderationRequest" class="org.eclipse.sw360.datahandler.thrift.moderation.ModerationRequest" scope="request"/>
<jsp:useBean id="licenseDetail" class="org.eclipse.sw360.datahandler.thrift.licenses.License" scope="request" />
<jsp:useBean id="isAdminUser" class="java.lang.String" scope="request" />
<jsp:useBean id="obligationList" type="java.util.List<org.eclipse.sw360.datahandler.thrift.licenses.Obligation>"
             scope="request"/>
<core_rt:set var="license" value="${licenseDetail}" scope="request"/>

<link rel="stylesheet" href="<%=request.getContextPath()%>/css/sw360.css">
<link rel="stylesheet" href="<%=request.getContextPath()%>/webjars/jquery-ui/themes/base/jquery-ui.min.css">
<script src="<%=request.getContextPath()%>/webjars/jquery/dist/jquery.min.js" type="text/javascript"></script>
<script src="<%=request.getContextPath()%>/webjars/jquery-validation/dist/jquery.validate.min.js" type="text/javascript"></script>
<script src="<%=request.getContextPath()%>/webjars/jquery-validation/dist/additional-methods.min.js" type="text/javascript"></script>
<script src="<%=request.getContextPath()%>/webjars/jquery-ui/jquery-ui.min.js"></script>

<portlet:actionURL var="editLicenseTodosURL" name="updateWhiteList">
    <portlet:param name="<%=PortalConstants.LICENSE_ID%>" value="${licenseDetail.id}" />
</portlet:actionURL>

<portlet:actionURL var="addLicenseTodoURL" name="addTodo">
    <portlet:param name="<%=PortalConstants.LICENSE_ID%>" value="${licenseDetail.id}" />
</portlet:actionURL>

<portlet:actionURL var="changeLicenseTextURL" name="changeText">
    <portlet:param name="<%=PortalConstants.LICENSE_ID%>" value="${licenseDetail.id}" />
</portlet:actionURL>

<portlet:actionURL var="editExternalLinkURL" name="editExternalLink">
    <portlet:param name="<%=PortalConstants.LICENSE_ID%>" value="${licenseDetail.id}" />
</portlet:actionURL>

<div id="header"></div>
<p class="pageHeader"><span class="pageHeaderBigSpan">Moderation Change License:  <sw360:LicenseName license="${license}"/></span>
</p>
<%@include file="/html/moderation/includes/moderationActionButtons.jspf"%>
<%@include file="/html/moderation/includes/moderationInfo.jspf"%>

<h2>Proposed changes</h2>

<h3>TODOs</h3>
<sw360:CompareTodos old="${licenseDetail.todos}"
                    update="${moderationRequest.licenseAdditions.todos}"
                    delete="${moderationRequest.licenseDeletions.todos}"
                    department="${moderationRequest.requestingUserDepartment}"
                    idPrefix=""
                    tableClasses="table info_table" />


<h2>Current license</h2>
<core_rt:set var="editMode" value="false" scope="request"/>

<%@include file="/html/licenses/includes/detailOverview.jspf"%>

<%@ include file="/html/utils/includes/requirejs.jspf" %>
<script>
    require(['jquery', 'modules/tabview'], function($, tabview) {
        tabview.create('myTab');

        $('td.addToWhiteListCheckboxes').hide();
        $('td.addToWhiteListCheckboxesPlaceholder').show();

        $('#EditWhitelist').on('click', function(event) {
            $('td.addToWhiteListCheckboxes').show();
            $('td.addToWhiteListCheckboxesPlaceholder').hide();
            $('tr.dependentOnWhiteList').show();
            $('#EditWhitelist').hide();
            $('#cancelEditWhitelistButton').show();
            $('#SubmitWhitelist').show();
        });
    });

    function getBaseURL(){
        var baseUrl = '<%= PortletURLFactoryUtil.create(request, portletDisplay.getId(), themeDisplay.getPlid(), PortletRequest.RENDER_PHASE) %>';
        var portletURL = Liferay.PortletURL.createURL(baseUrl)
                .setParameter('<%=PortalConstants.PAGENAME%>', '<%=PortalConstants.PAGENAME_ACTION%>')
                .setParameter('<%=PortalConstants.MODERATION_ID%>', '${moderationRequest.id}')
                .setParameter('<%=PortalConstants.DOCUMENT_TYPE%>', '<%=DocumentType.LICENSE%>');

        return portletURL;
    }
</script>
<%@include file="/html/moderation/includes/moderationActions.jspf"%>

