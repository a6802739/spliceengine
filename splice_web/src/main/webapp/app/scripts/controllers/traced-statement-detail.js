'use strict';

angular.module('spliceWebApp')
  .controller('TracedStatementDetailCtrl', ['$scope', '$routeParams', 'TracedStatementDetailService', function ($scope, $routeParams, TracedStatementDetailService) {
	  var str = TracedStatementDetailService.get({statementId: $routeParams.statementId});
	  $scope.tracedStatement = str;
	  $scope.tracedStatementString = JSON.stringify(str, undefined, 2);
	  $scope.awesomeThings = [
      'HTML5 Boilerplate',
      'AngularJS',
      'Karma'
    ];
  }]);
