/**
 * 
 */
var app = angular.module('AlogApp', ['ui.bootstrap']);
//var app = angular.module('AlogApp', ['ui.bootstrap','ui.bootstrap.dropdown','ui.bootstrap.datetimepicker']);



/**
 * datepicker setup
 */
app.controller('DateTimeCtrl', ['$scope', function ($scope) {
	$scope.today = function() {
		$scope.anchorDate = new Date();
	};
	$scope.today();

	$scope.clear = function () {
	    $scope.anchorDate = null;
	 };

	 $scope.open = function($event) {
		 $event.preventDefault();
		 $event.stopPropagation();
		 $scope.opened = true;
	 };

	$scope.dateOptions = {
		formatYear: 'yy',
		startingDay: 1
	};
	$scope.dateFormat = 'dd.MM.yyyy';
}]);

app.controller('FilterSelectionCtrl', ['$scope', 'features', '$http', function($scope, features, $http) {
	$scope.oneAtATime = false;
	$scope.status = {
		isFilterOpen: false,
		isDisplayOpen: false,
		isGroupOpen: false
	};
	$scope.featuresVM = new FeatureListVM($scope, features, $http)
}]);

function FeatureListVM(scope, features, http) {
	var that = this;
	this.scope = scope;
	this.featuresSvc = features;
	this.http = http;
	this.features = [];
	
	this.loadFeatures = function() {
		that.featuresSvc.load().then(function(data) {
			that.features = data 
		});
	}
	
	this.getValueProposals = function(key, viewValue) {
		return http.get('/features/' + key, {
		      params: {
		        prefix: viewValue
		      }
		    }).then(function(res){
		      var values = [];
		      angular.forEach(res.data, function(item){
		    	  values.push(item);
		      });
		      return values;
		    });
		  };
		  
	this.setAll = function(target, value) {
		angular.forEach(that.features, function(f) {
			target[f] = value;
		});
	};
}

app.factory('alog', ['$http', '$q', '$timeout', function($http, $q, $timeout){
	return {
		load: function(querySettings) {
			var d = $q.defer();
			$http.get('/log/entries',{params:querySettings}).then(
				function(result) {
					d.resolve(result.data);
	            }, function (reason) {
	                d.reject(reason);
	            }
            );
			return d.promise;
		}
	}
}]);

app.factory('features', ['$http', '$q', '$timeout', function($http, $q, $timeout){
	return {
		load: function() {
			var d = $q.defer();
			$http.get('/features').then(
				function(result) {
					d.resolve(result.data);
	            }, function (reason) {
	                d.reject(reason);
	            }
            );
			return d.promise;
		}
	}
}]);

app.factory('alogDetail', ['$http', '$q', '$timeout', function($http, $q, $timeout) {
	return {
		load: function(logId) {
			var d = $q.defer();
			$http.get('/log/entries/' + logId).then(
				function(result) {
					d.resolve(result.data);
	            }, function (reason) {
	                d.reject(reason);
	            }
            );
			return d.promise;
		}
	};
}]);

app.controller('AlogCtrl', ['$scope', 'alog', 'alogUtils', '$http', '$filter', '$modal', '$timeout', function($scope, alog, alogUtils, $http, $filter, $modal, $timeout) {
	$scope.logVM = new LogListVM($scope, $modal, alog, alogUtils, $filter, $timeout);
	
	var durFldFn = function(n,o) {
		if (n != o) {
			$scope.logVM.querySettings.qfrom = '';
			$scope.logVM.querySettings.qtill = '';
			$scope.logVM.querySettings.qduration = '';
			var dur1, dur2;
			if ($scope.logVM.isDateChooserVisible('from')) {
				$scope.logVM.querySettings.qfrom = $filter('date')($scope.logVM.uiSettings.dateFrom, alogUtils.stdDateFormat.datetime)
			}
			if ($scope.logVM.isDateChooserVisible('till')) {
				$scope.logVM.querySettings.qtill = $filter('date')($scope.logVM.uiSettings.dateTill, alogUtils.stdDateFormat.datetime)
			}
			if ($scope.logVM.isDateChooserVisible('dur')) {
				$scope.logVM.querySettings.qduration = "" + $scope.logVM.uiSettings.duration + $scope.logVM.uiSettings.durationCat.key  
			}
			$scope.$broadcast("parameterChanged");
		}
	};
	$scope.$watch('logVM.uiSettings.dateMode', durFldFn);
	$scope.$watch('logVM.uiSettings.dateFrom', durFldFn);
	$scope.$watch('logVM.uiSettings.dateTill', durFldFn);
	$scope.$watch('logVM.uiSettings.duration', durFldFn);
	$scope.$watch('logVM.uiSettings.durationCat', durFldFn);
	
	$scope.$on("parameterChanged", function() {$scope.logVM.loadLogEntries() });
	
	$timeout(function(){ 
		$scope.$broadcast("parameterChanged"); 
	}, 300);
}]);

function LogListVM(scope, modal, alog, utils, $filter, $timeout) {
	var that = this;
	this.scope = scope;
	this.modal = modal;
	this.alog = alog;
	this.utils = utils;
	
	this.featureDisplayed = {};
	this.featureGrouped = {};
	
	this.uiSettings = {
		dateFrom: that.utils.dateWithDeltaDays(-1),
		dateTill: that.utils.dateWithDeltaDays(0),
		duration: 24,
		durationCat: {key:'h', label:'Stunden'},
		dateMode: 'from_till',
		viewOffset: 0
	}
	this.isDateChooserVisible = function(key) {
		return that.uiSettings.dateMode.indexOf(key) > -1;
	}
	
	this.querySettings = {
		qfrom: $filter('date')(this.uiSettings.dateFrom, that.utils.stdDateFormat.datetime),
		qtill: $filter('date')(this.uiSettings.dateFrom, that.utils.stdDateFormat.datetime),
		qduration: '24h',
		qdetail: 'full',
		qlimit: 20,
		qmaxlen: 220
	};
	
	this.logEntries = {}
	
	this.util = {
		durationCats : [
			{label: "Tage", key:"d"},
			{label: "Stunden", key:"h"},
			{label: "Minuten", key:"m"},
			{label: "Sekunden", key:"s"},
		]
	}
	
	this.isLoadWaiting = false;
	this.isLoadingLogEntries = false;
	
	this.loadLogEntries = function() {
		var doLoad = function() {
			that.uiSettings.viewOffset = 0;
			that.scope.$broadcast("logentries.loading")
			that.alog.load(that.querySettings).then(function(data) {
				that.logEntries = data.full
				that.scope.$broadcast("logentries.loaded")
			});
		}
		if (!that.isLoadingLogEntries) {
			that.isLoadWaiting = false;
			that.isLoadingLogEntries = true;
			doLoad();
			$timeout(function(){
				that.isLoadingLogEntries = false;
				if (that.isLoadWaiting) {
					that.loadLogEntries();
				}
			}, 1000);
		} else {
			that.isLoadWaiting = true;
		}
	}
	
	this.loadMore = function() {
		var offset = (that.uiSettings.viewOffset || 0) + that.querySettings.qlimit;
		var settings = angular.copy(that.querySettings)
		settings.qoffset = that.uiSettings.viewOffset = offset;
		alog.load(settings).then(function(data) {
			angular.forEach(data.full, function(e) {
				this.push(e);
			}, that.logEntries)
		});
	}
	
	this.showDetail = function(index) {
		var modalInstance = this.modal.open({
			templateUrl: 'logDetails.html',
			controller: LogDetailsCtrl,
			size: 'lg',
			resolve: {
				logEntries: function () {
					return that.logEntries;
				},
				logIndex: function () {
					return index;
				}
			}
		});

		modalInstance.result.then(function (res) {  
			if (res && res.focusTimespan) {
				var e = that.logEntries[res.focusAround];
				var datetime = moment(e.timestamp, "DD.MM.YYYY/HH:mm:ss,SSS").toDate()
				that.uiSettings.dateFrom = new Date(datetime.getTime() - res.focusTimespan*1000)
				that.uiSettings.dateTill = new Date(datetime.getTime() + res.focusTimespan*1000)
				that.uiSettings.dateMode = "from_till"
				that.scope.$broadcast("parameterChanged");
			}
		});
//		, function () {
//		});
	};
	
	this.setFilter = function(prop, val) {
		that.querySettings[prop] = (that.querySettings[prop] == val) ? null: val;
		that.scope.$broadcast("parameterChanged");
	}
	
	this.clearFilters = function(features) {
		angular.forEach(features, function(feat){
			that.querySettings[feat] = "";
		});
		that.scope.$broadcast("parameterChanged");
	}

	this.adjustDate = function(seconds) {
		if (that.uiSettings.dateMode.indexOf("from") > -1) {
			that.uiSettings.dateFrom = new Date(that.uiSettings.dateFrom.getTime() + seconds*1000)
		}
		if (that.uiSettings.dateMode.indexOf("till") > -1) {
			var tillVal = that.uiSettings.dateTill ? that.uiSettings.dateTill : new Date();
			that.uiSettings.dateTill = new Date(tillVal.getTime() + seconds*1000)
			that.scope.$broadcast("parameterChanged");
		}
	}
	
	this.setLive = function() {
		that.uiSettings.dateTill = "";
		that.uiSettings.dateMode = "dur_till";
		if (!(parseInt(that.uiSettings.duration) < 15) || that.uiSettings.durationCat.key != 'm') {
			that.uiSettings.duration = "15";
			that.uiSettings.durationCat = that.util.durationCats[2]; 
		}
		that.scope.$broadcast("parameterChanged");
	}
}

var LogDetailsCtrl = function ($scope, $modalInstance, alogDetail, logEntries, logIndex) {
	$scope.logDetailsVM = new LogDetailsVM($scope, $modalInstance, alogDetail, logEntries, logIndex);
	$scope.logDetailsVM.load();
};

function LogDetailsVM(scope, modalInstance, alogDetail, logEntries, logIndex) {
	var that = this;
	this.scope = scope;
	this.alogDetail = alogDetail;
	this.currentIndex = logIndex;
	this.maxIndex = logEntries.length - 1;
	this.focusTimeDropdown = false;
	
	this.logEntry = {
	};
	
	this.load = function() {
		alogDetail.load(logEntries[that.currentIndex].__logId).then(function(data) {
			that.logEntry = data
		});
	};
	
	this.move = function(rel) {
		that.currentIndex += rel;
		that.load();
	}
	
	this.ok = function() {
		modalInstance.close();
	};
	
	this.okAndFocus = function(span) {
		modalInstance.close({focusTimespan: span, focusAround: that.currentIndex});
	}
}
