var app = angular.module('AlogApp');

app.controller('AlogCometCtrl', ['$scope', '$filter', '$q', '$http', 'alogComet',  function ($scope, $filter, $q, $http, alogComet) {
	var cometVM = new CometVM($scope, $filter, alogComet);
	$scope.cometVM = cometVM;
	
	$scope.$on("parameterChanged", function() {
		$scope.cometVM.activate = (!$scope.logVM.uiSettings.dateTill && ($scope.logVM.uiSettings.dateMode == "dur_till" || $scope.logVM.uiSettings.dateMode == "from_till"));
	});
	$scope.$on("logentries.loaded", function(evt){
		alogComet.unregister($scope, "logComet").then(function() {
			if ($scope.cometVM.activate) {
				alogComet.start($scope, "/log/tail", $scope.logVM.querySettings, "logComet");
			}
		});
	});
	
}]);

function CometVM($scope, $filter, alogComet) {
	var that = this;
	
	this.activate = false;
	this.alogComet = alogComet;
	this.filter = $filter;
	this.scope = $scope;
	
	this.cometRequests = [];
	this.activeComet = {};
}

app.factory('alogComet', ['$http', '$q', '$timeout', function($http, $q, $timeout) {
	var that = {
		start: function($scope, cometGate, params, topic) {
			var params_ = angular.copy(params)
			if ($scope.cometVM.activeComet.qtail) {
				params_.qtail = $scope.cometVM.activeComet.qtail;
			}
			if (topic) {
				params_.qtailtopic = topic;
			}
			var d = $q.defer();
			$http.post(cometGate, params_ ).
				success(function(result) {
					var prev = $scope.cometVM.activeComet
					$scope.cometVM.activeComet = result;
					$scope.cometVM.cometRequests.push({
						url: cometGate,
						params: params,
						topic: topic
					});
					d.resolve(result.href);
					if (!prev || prev.qtail != result.qtail) {
						that.run($scope)
					}
	            }).
	            error(function(reason) {
	                d.reject(reason);
	            });
			return d.promise;
		},
		
		restart: function($scope) {
			angular.forEach( $scope.cometVM.cometRequests, function(r){
				
			});
		},
		
		unregister: function($scope, topic) {
			var d = $q.defer();
			if(topic && $scope.cometVM.activeComet.href) {
				$http.delete($scope.cometVM.activeComet.href + "/" + topic).
					success(function(res){
						d.resolve("ok");
					}).error(function(reason){
						d.resolve("nok: " + reason)
					});
			} else {
				d.resolve("noop");
			}
			return d.promise;
		},
		
		run: function($scope) {
			$http.get($scope.cometVM.activeComet.href, {timeout: 30000}).
				success (function(result, status) {
					if (status != 204) {
						// promote result
						angular.forEach(result, function(v,k) {
							$scope.$broadcast(k,v);
						});
					}
					$timeout(function() {that.run($scope)}, 10)
				}).
				error (function(data, status) {
					$scope.cometVM.activeComet = {};
					if (status != 409 && status != 410) {
						console.log("comet aborted unexpectedly, status " + status)
					}
				});
		}
	};
	return that;
}]);

app.controller('LogUpdateCometCtrl', ['$scope', '$filter', 'alogUtils', function($scope, $filter, alogUtils) {
	var vm = new LogUpdateVM($scope, $filter);
	$scope.logUpdateVM = vm;
	$scope.logUpdateVM.logCount = 0;
	$scope.$on("logComet", function(l,d) {
		var f = moment(d.first, "DD.MM.YYYY/HH:mm:ss,SSS").toDate();
		var l = moment(d.last, "DD.MM.YYYY/HH:mm:ss,SSS").toDate();
		$scope.logUpdateVM.logCount += (d.count || 0);
		if (!$scope.logUpdateVM.first || f < $scope.logUpdateVM.first) {
			$scope.logUpdateVM.first = f
		}
		if (!$scope.logUpdateVM.last || l > $scope.logUpdateVM.last) {
			$scope.logUpdateVM.last = l
		}
	});
	$scope.$on("logentries.loaded", function(evt){
		$scope.logUpdateVM.reset();
	});
}]);

function LogUpdateVM($scope, $filter) {
	var that = this;
	this.scope = $scope;
	this.filter = $filter;
	
	this.from = '';
	this.till = '';
	this.logCount = 0;
	
	this.reset = function() {
		that.first = '';
		that.last = '';
		that.logCount = 0;
	};
	
	this.setQueryTimerange = function() {
		if (that.first && that.last) {
			that.scope.logVM.uiSettings.dateFrom = that.first;
//			that.scope.logVM.uiSettings.dateTill = that.last;
			that.scope.logVM.uiSettings.dateTill = '';
			that.scope.logVM.uiSettings.dateMode = "from_till";
			$scope.$emit("parameterChanged");
		}
	}
}
